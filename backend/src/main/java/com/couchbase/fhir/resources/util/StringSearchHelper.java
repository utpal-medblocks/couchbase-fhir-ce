package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.RuntimePrimitiveDatatypeDefinition;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.client.java.search.SearchQuery;
import org.hl7.fhir.instance.model.api.IBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds Couchbase FTS queries for FHIR string search parameters using HAPI runtime introspection.
 * - Supports FHIRPath union expressions via FHIRPathParser (provided elsewhere in your codebase).
 * - Dynamically expands composite elements (HumanName, Address, ContactPoint, etc.) to their
 *   string-bearing children (e.g., name.family, address.city, telecom.value, alias).
 * - Handles :exact modifier by mapping field -> fieldExact.
 */
public class StringSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(StringSearchHelper.class);

    /** Primitive datatypes we consider "string-like" for search. */
    private static final Set<Class<?>> STRINGY_PRIMITIVES = new HashSet<>(Arrays.asList(
        org.hl7.fhir.r4.model.StringType.class,
        org.hl7.fhir.r4.model.MarkdownType.class,
        org.hl7.fhir.r4.model.CodeType.class,
        org.hl7.fhir.r4.model.UriType.class
        // Explicitly NOT including: IdType, DateType, BooleanType, IntegerType, Period, Extension, etc.
    ));

    /** Simple cache for expansions: key = resourceType + "|" + fieldPath (post-FHIRPath parse). */
    private static final Map<String, List<String>> EXPANSION_CACHE = new ConcurrentHashMap<>();

    /**
     * Build an FTS query for a single string value.
     */
    public static SearchQuery buildStringFTSQuery(
        FhirContext fhirContext,
        String resourceType,
        String paramName,
        String searchValue,
        RuntimeSearchParam searchParam,
        String modifier
    ) {
        String rawPath = (searchParam != null) ? searchParam.getPath() : null;
        logger.debug("🔍 StringSearchHelper: paramName={}, rawPath={}", paramName, rawPath);

        if (rawPath == null || rawPath.isEmpty()) {
            logger.warn("🔍 StringSearchHelper: Empty/unknown path for paramName={}", paramName);
            return null;
        }

        // Parse FHIRPath (supports unions like "name | Organization.alias")
        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(rawPath);

        List<String> fieldPaths = new ArrayList<>();
        if (parsed.isUnion() && parsed.getFieldPaths().size() > 1) {
            for (String path : parsed.getFieldPaths()) {
                fieldPaths.addAll(expandStringField(fhirContext, resourceType, normalizeFieldPath(resourceType, path)));
            }
            logger.debug("🔍 StringSearchHelper: Parsed union -> {} fields: {}", fieldPaths.size(), fieldPaths);
        } else {
            String fieldPath = parsed.getPrimaryFieldPath();
            if (fieldPath == null) {
                // Fallback to legacy parsing: strip "ResourceType." prefix if present
                String fhirPath = rawPath.replaceFirst("^" + resourceType + "\\.", "");
                fieldPath = fhirPath;
            }
            fieldPath = normalizeFieldPath(resourceType, fieldPath);
            List<String> expanded = expandStringField(fhirContext, resourceType, fieldPath);
            fieldPaths.addAll(expanded);
            logger.debug("🔍 StringSearchHelper: Single field '{}' expanded to: {}", fieldPath, expanded);
        }

        if (fieldPaths.isEmpty()) {
            logger.warn("🔍 StringSearchHelper: No field paths found for paramName={}, rawPath={}", paramName, rawPath);
            return null;
        }

        boolean isExact = "exact".equalsIgnoreCase(modifier);
        List<SearchQuery> perFieldQueries = new ArrayList<>(fieldPaths.size());
        for (String field : fieldPaths) {
            if (isExact) {
                String exactField = field + "Exact";
                perFieldQueries.add(SearchQuery.match(searchValue).field(exactField));
            } else {
                // Lowercase for prefix search consistency; ensure your indexing normalizes similarly.
                perFieldQueries.add(SearchQuery.prefix(searchValue == null ? "" : searchValue.toLowerCase()).field(field));
            }
        }

        // OR across all expanded fields for this parameter
        return SearchQuery.disjuncts(perFieldQueries.toArray(new SearchQuery[0]));
    }

    /**
     * Build an FTS query for multiple values (OR between values).
     */
    public static SearchQuery buildStringFTSQueryWithMultipleValues(
        FhirContext fhirContext,
        String resourceType,
        String paramName,
        List<String> searchValues,
        RuntimeSearchParam searchParam,
        String modifier
    ) {
        if (searchValues == null || searchValues.isEmpty()) {
            return null;
        }
        if (searchValues.size() == 1) {
            return buildStringFTSQuery(fhirContext, resourceType, paramName, searchValues.get(0), searchParam, modifier);
        }

        List<SearchQuery> valueQueries = new ArrayList<>();
        for (String value : searchValues) {
            SearchQuery q = buildStringFTSQuery(fhirContext, resourceType, paramName, value, searchParam, modifier);
            if (q != null) valueQueries.add(q);
        }

        if (valueQueries.isEmpty()) return null;
        if (valueQueries.size() == 1) return valueQueries.get(0);
        return SearchQuery.disjuncts(valueQueries.toArray(new SearchQuery[0]));
    }

    /**
     * Expand a field path to actual string-bearing leaf field paths using HAPI runtime introspection.
     * Rules:
     * - If the path resolves to a string primitive -> return it as-is.
     * - If the path resolves to a composite -> return its string-bearing children (one level deep).
     * - If nothing found, attempt a common ".text" child on composites (e.g., HumanName.text, Address.text).
     * - If still nothing found, return the original path to avoid losing the signal.
     */
    public static List<String> expandStringField(FhirContext fhirContext, String resourceType, String fieldPath) {
        String cacheKey = resourceType + "|" + fieldPath;
        List<String> cached = EXPANSION_CACHE.get(cacheKey);
        if (cached != null) return cached;

        logger.debug("🔍 StringSearchHelper(dynamic): Expanding '{}' for {}", fieldPath, resourceType);

        RuntimeResourceDefinition rrd = fhirContext.getResourceDefinition(resourceType);
        if (rrd == null) {
            logger.warn("No resource definition for type {}", resourceType);
            List<String> fallback = Collections.singletonList(fieldPath);
            EXPANSION_CACHE.put(cacheKey, fallback);
            return fallback;
        }

        String[] tokens = fieldPath.split("\\.");
        BaseRuntimeElementDefinition<?> currentDef = rrd;
        BaseRuntimeElementCompositeDefinition<?> currentComposite = rrd;

        for (String token : tokens) {
            if (currentComposite == null) {
                // We hit a primitive earlier; remaining tokens can't be resolved
                logger.warn("Path '{}' cannot be resolved beyond primitive at '{}'", fieldPath, token);
                List<String> fallback = Collections.singletonList(fieldPath);
                EXPANSION_CACHE.put(cacheKey, fallback);
                return fallback;
            }

            BaseRuntimeChildDefinition child = currentComposite.getChildByName(token);
            if (child == null) {
                logger.warn("Child '{}' not found under '{}'", token, currentComposite.getName());
                List<String> fallback = Collections.singletonList(fieldPath);
                EXPANSION_CACHE.put(cacheKey, fallback);
                return fallback;
            }

            BaseRuntimeElementDefinition<?> chosen = chooseElementDef(fhirContext, child);
            if (chosen == null) {
                logger.warn("No usable child type for '{}'", token);
                List<String> fallback = Collections.singletonList(fieldPath);
                EXPANSION_CACHE.put(cacheKey, fallback);
                return fallback;
            }

            currentDef = chosen;
            currentComposite = (chosen instanceof BaseRuntimeElementCompositeDefinition)
                ? (BaseRuntimeElementCompositeDefinition<?>) chosen
                : null;
        }

        // If we ended on a primitive:
        if (!(currentDef instanceof BaseRuntimeElementCompositeDefinition)) {
            if (isStringyPrimitive(currentDef)) {
                List<String> out = Collections.singletonList(fieldPath);
                EXPANSION_CACHE.put(cacheKey, out);
                return out;
            } else {
                // Not a string primitive -> nothing to index for a "string" search
                List<String> out = Collections.emptyList();
                EXPANSION_CACHE.put(cacheKey, out);
                return out;
            }
        }

        // We ended on a composite: collect its string-bearing children (one level deep).
        BaseRuntimeElementCompositeDefinition<?> composite = (BaseRuntimeElementCompositeDefinition<?>) currentDef;
        List<String> expanded = new ArrayList<>();

        for (BaseRuntimeChildDefinition child : composite.getChildren()) {
            String elementName = child.getElementName();
            
            // Skip inherited Element fields that aren't relevant for string search
            if ("id".equals(elementName) || "extension".equals(elementName)) {
                continue;
            }
            
            BaseRuntimeElementDefinition<?> childDef = chooseElementDef(fhirContext, child);
            if (childDef == null) continue;

            if (isStringyPrimitive(childDef)) {
                expanded.add(fieldPath + "." + elementName); // e.g., name.family, address.city, telecom.value, alias
            }
            // Skip composite types (Extension, Period, etc.) - they contain non-string fields
        }

        // Fallback: try a common ".text" property if nothing found yet (e.g., HumanName.text, Address.text)
        if (expanded.isEmpty()) {
            BaseRuntimeChildDefinition textChild = composite.getChildByName("text");
            if (textChild != null) {
                BaseRuntimeElementDefinition<?> textDef = chooseElementDef(fhirContext, textChild);
                if (isStringyPrimitive(textDef)) {
                    expanded.add(fieldPath + ".text");
                }
            }
        }

        // Final fallback: keep the original path so callers still index something (important for alias-like fields)
        if (expanded.isEmpty()) {
            expanded.add(fieldPath);
        }

        List<String> immutable = Collections.unmodifiableList(expanded);
        EXPANSION_CACHE.put(cacheKey, immutable);
        return immutable;
    }

    // ---------- Helpers ----------

    private static boolean isStringyPrimitive(BaseRuntimeElementDefinition<?> def) {
        if (def == null) return false;
        if (!(def instanceof RuntimePrimitiveDatatypeDefinition)) return false;
        Class<?> clazz = def.getImplementingClass();
        for (Class<?> s : STRINGY_PRIMITIVES) {
            if (s.isAssignableFrom(clazz)) return true;
        }
        return false;
    }

    private static BaseRuntimeElementDefinition<?> chooseElementDef(FhirContext ctx, BaseRuntimeChildDefinition child) {
        // Try to get the child definition directly
        BaseRuntimeElementDefinition<?> childDef = child.getChildByName(child.getElementName());
        if (childDef != null) return childDef;
        
        // For choice elements, get the first valid type
        if (child instanceof ca.uhn.fhir.context.RuntimeChildChoiceDefinition) {
            ca.uhn.fhir.context.RuntimeChildChoiceDefinition choiceDef = 
                (ca.uhn.fhir.context.RuntimeChildChoiceDefinition) child;
            for (Class<? extends IBase> type : choiceDef.getValidChildTypes()) {
                return ctx.getElementDefinition(type);
            }
        }
        return null;
    }

    private static String normalizeFieldPath(String resourceType, String path) {
        if (path == null) return "";
        if (path.startsWith(resourceType + ".")) {
            return path.substring(resourceType.length() + 1);
        }
        return path;
    }
}
