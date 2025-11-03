package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.*;
import com.couchbase.client.java.search.SearchQuery;
import org.hl7.fhir.instance.model.api.IBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class QuantitySearchHelper {
    private static final Logger logger = LoggerFactory.getLogger(QuantitySearchHelper.class);
    private static final Map<String, List<String>> EXPANSION_CACHE = new ConcurrentHashMap<>();

    private static final Set<Class<?>> NUMERIC_PRIMITIVES = new HashSet<>(Arrays.asList(
            org.hl7.fhir.r4.model.IntegerType.class,
            org.hl7.fhir.r4.model.UnsignedIntType.class,
            org.hl7.fhir.r4.model.PositiveIntType.class,
            org.hl7.fhir.r4.model.DecimalType.class
    ));

    public static SearchQuery buildQuantityFTSQuery(
            FhirContext fhirContext,
            String resourceType,
            String paramName,
            String rawSearchValue,
            RuntimeSearchParam searchParam
    ) {
        String rawPath = (searchParam != null) ? searchParam.getPath() : null;
        logger.debug("🔢 QuantitySearchHelper: paramName={}, rawPath={}, rawValue={}", paramName, rawPath, rawSearchValue);

        if (rawPath == null || rawPath.isEmpty()) {
            logger.warn("QuantitySearchHelper: Empty/unknown path for paramName={}", paramName);
            return null;
        }

        // Parse comparator prefix (eq, ne, lt, le, gt, ge, ap)
        String prefix = parsePrefix(rawSearchValue);
        String valuePart = stripPrefix(rawSearchValue);
        double value;
        try {
            value = Double.parseDouble(valuePart);
        } catch (NumberFormatException e) {
            logger.error("Invalid quantity value for search: {}", rawSearchValue);
            return null;
        }

        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(rawPath);

        List<String> fieldPaths = new ArrayList<>();
        if (parsed.isUnion() && parsed.getFieldPaths().size() > 1) {
            for (String path : parsed.getFieldPaths()) {
                // Normalize and expand only numeric fields (Quantity)
                fieldPaths.addAll(expandQuantityField(fhirContext, resourceType, normalizeFieldPath(resourceType, path)));
            }
        } else {
            String fieldPath = parsed.getPrimaryFieldPath();
            fieldPaths.addAll(expandQuantityField(fhirContext, resourceType, normalizeFieldPath(resourceType, fieldPath)));
        }

        if (fieldPaths.isEmpty()) {
            logger.warn("No quantity fields found for {}", rawPath);
            return null;
        }

        List<SearchQuery> perFieldQueries = new ArrayList<>();
        for (String field : fieldPaths) {
            SearchQuery q = buildNumericRangeQuery(field, prefix, value);
            if (q != null) perFieldQueries.add(q);
        }

        if (perFieldQueries.isEmpty()) return null;
        if (perFieldQueries.size() == 1) return perFieldQueries.get(0);
        return SearchQuery.disjuncts(perFieldQueries.toArray(new SearchQuery[0]));
    }

    private static SearchQuery buildNumericRangeQuery(String field, String prefix, double value) {
        switch (prefix) {
            case "eq":
                return SearchQuery.numericRange().min(value, true).max(value, true).field(field);
            case "ne":
                return SearchQuery.disjuncts(
                        SearchQuery.numericRange().max(value, false).field(field),
                        SearchQuery.numericRange().min(value, false).field(field)
                );
            case "lt":
                return SearchQuery.numericRange().max(value, false).field(field);
            case "le":
                return SearchQuery.numericRange().max(value, true).field(field);
            case "gt":
                return SearchQuery.numericRange().min(value, false).field(field);
            case "ge":
                return SearchQuery.numericRange().min(value, true).field(field);
            case "ap":
                double delta = value * 0.1;
                return SearchQuery.numericRange().min(value - delta, true).max(value + delta, true).field(field);
            default:
                return SearchQuery.numericRange().min(value, true).max(value, true).field(field);
        }
    }


    public static List<String> expandQuantityField(FhirContext fhirContext, String resourceType, String fieldPath) {
        String cacheKey = resourceType + "|" + fieldPath;
        List<String> cached = EXPANSION_CACHE.get(cacheKey);
        if (cached != null) return cached;

        List<String> expanded = new ArrayList<>();
        RuntimeResourceDefinition rrd = fhirContext.getResourceDefinition(resourceType);
        if (rrd == null) {
            EXPANSION_CACHE.put(cacheKey, Collections.unmodifiableList(expanded));
            return expanded;
        }

        String[] tokens = fieldPath.split("\\.");
        BaseRuntimeElementDefinition<?> currentDef = rrd;
        BaseRuntimeElementCompositeDefinition<?> currentComposite = rrd;

        for (String token : tokens) {
            if (currentComposite == null) {
                currentDef = null;
                break;
            }

            // Attempt direct child first (e.g., valueQuantity)
            BaseRuntimeChildDefinition child = currentComposite.getChildByName(token);

            // If not found, try to resolve choice elements by preferring *Quantity* children
            if (child == null) {
                for (BaseRuntimeChildDefinition candidate : currentComposite.getChildren()) {
                    if (candidate instanceof ca.uhn.fhir.context.RuntimeChildChoiceDefinition) {
                        ca.uhn.fhir.context.RuntimeChildChoiceDefinition choice =
                                (ca.uhn.fhir.context.RuntimeChildChoiceDefinition) candidate;

                        // Look for a valid child name that ends with "Quantity" (valueQuantity, somethingQuantity)
                        for (String validName : choice.getValidChildNames()) {
                            if (validName.endsWith("Quantity") && validName.startsWith(token)) {
                                // concrete child name found (e.g., valueQuantity) -> use it
                                child = currentComposite.getChildByName(validName);
                                break;
                            }
                        }
                        if (child != null) break;
                    }

                    // Defensive: candidate element name might itself endWith Quantity and start with token
                    if (candidate.getElementName().startsWith(token) && candidate.getElementName().endsWith("Quantity")) {
                        child = candidate;
                        break;
                    }
                }
            }

            if (child == null) {
                // couldn't resolve token -> stop
                currentDef = null;
                break;
            }

            BaseRuntimeElementDefinition<?> chosen = chooseElementDef(fhirContext, child , "Quantity");
            if (chosen == null) {
                currentDef = null;
                break;
            }

            currentDef = chosen;
            currentComposite = (chosen instanceof BaseRuntimeElementCompositeDefinition)
                    ? (BaseRuntimeElementCompositeDefinition<?>) chosen
                    : null;
        }

        // ONLY handle Quantity / SimpleQuantity -> map to ".value"
        if (currentDef != null) {
            String defName = currentDef.getName();
            if ("Quantity".equals(defName) || "SimpleQuantity".equals(defName)) {
                String[] parts = fieldPath.split("\\.");
                String prefix = String.join(".", Arrays.copyOf(parts, parts.length)); // start with original path
                String lastToken = parts[parts.length - 1];
                if (lastToken.endsWith("Quantity")) {
                    expanded.add(fieldPath + ".value");
                }
            }
        }

        List<String> result = Collections.unmodifiableList(expanded);
        EXPANSION_CACHE.put(cacheKey, result);
        return result;
    }



    /*
    public static List<String> expandNumericField(FhirContext fhirContext, String resourceType, String fieldPath) {
        String cacheKey = resourceType + "|" + fieldPath;
        if (EXPANSION_CACHE.containsKey(cacheKey)) {
            return EXPANSION_CACHE.get(cacheKey);
        }

        List<String> expanded = new ArrayList<>();
        RuntimeResourceDefinition rrd = fhirContext.getResourceDefinition(resourceType);
        if (rrd == null) return expanded;

        String[] tokens = fieldPath.split("\\.");
        BaseRuntimeElementDefinition<?> currentDef = rrd;
        BaseRuntimeElementCompositeDefinition<?> currentComposite = rrd;

        for (String token : tokens) {
            if (currentComposite == null) break;
            BaseRuntimeChildDefinition child = currentComposite.getChildByName(token);
            if (child == null) break;

            BaseRuntimeElementDefinition<?> chosen = chooseElementDef(fhirContext, child);
            if (chosen == null) break;
            currentDef = chosen;
            currentComposite = (chosen instanceof BaseRuntimeElementCompositeDefinition)
                    ? (BaseRuntimeElementCompositeDefinition<?>) chosen
                    : null;
        }

        if (!(currentDef instanceof BaseRuntimeElementCompositeDefinition)) {
            if (isNumericPrimitive(currentDef)) {
                expanded.add(fieldPath);
            }
        }

        EXPANSION_CACHE.put(cacheKey, expanded);
        return expanded;
    }


     */
    private static boolean isNumericPrimitive(BaseRuntimeElementDefinition<?> def) {
        if (def == null) return false;
        if (!(def instanceof RuntimePrimitiveDatatypeDefinition)) return false;
        Class<?> clazz = def.getImplementingClass();
        for (Class<?> n : NUMERIC_PRIMITIVES) {
            if (n.isAssignableFrom(clazz)) return true;
        }
        return false;
    }

    private static BaseRuntimeElementDefinition<?> chooseElementDef(FhirContext ctx, BaseRuntimeChildDefinition child, String desiredType) {
        BaseRuntimeElementDefinition<?> childDef = child.getChildByName(child.getElementName());
        if (childDef != null) {
            return childDef;
        }

        if (child instanceof RuntimeChildChoiceDefinition) {
            RuntimeChildChoiceDefinition choiceDef = (RuntimeChildChoiceDefinition) child;
            for (Class<? extends IBase> type : choiceDef.getValidChildTypes()) {
                BaseRuntimeElementDefinition<?> def = ctx.getElementDefinition(type);
                if (def != null && def.getName().equalsIgnoreCase(desiredType)) {
                    return def;
                }
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


    private static String parsePrefix(String raw) {
        if (raw == null) return "eq";
        if (raw.length() < 2) return "eq";
        String prefix = raw.substring(0, 2);
        switch (prefix) {
            case "eq":
            case "ne":
            case "lt":
            case "le":
            case "gt":
            case "ge":
            case "ap":
                return prefix;
            default:
                return "eq";
        }
    }

    private static String stripPrefix(String raw) {
        if (raw == null) return "0";
        if (raw.length() < 2) return raw;
        String prefix = raw.substring(0, 2);
        switch (prefix) {
            case "eq":
            case "ne":
            case "lt":
            case "le":
            case "gt":
            case "ge":
            case "ap":
                return raw.substring(2);
            default:
                return raw;
        }
    }

}
