package com.couchbase.fhir.resources.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized FHIRPath expression parser for all search helpers
 * Handles complex expressions from HAPI, US Core, and future custom implementations
 */
public class FHIRPathParser {
    
    private static final Logger logger = LoggerFactory.getLogger(FHIRPathParser.class);
    
    /**
     * Represents a parsed FHIRPath expression with all its components
     */
    public static class ParsedExpression {
        private final String originalExpression;
        private final ExpressionType type;
        private final List<String> fieldPaths;
        private final String extensionUrl;
        private final String extensionValueField;
        private final boolean isUnion;
        private final boolean isExtension;
        
        public ParsedExpression(String originalExpression, ExpressionType type, 
                              List<String> fieldPaths, String extensionUrl, String extensionValueField) {
            this.originalExpression = originalExpression;
            this.type = type;
            this.fieldPaths = fieldPaths != null ? fieldPaths : new ArrayList<>();
            this.extensionUrl = extensionUrl;
            this.extensionValueField = extensionValueField;
            this.isUnion = type == ExpressionType.UNION;
            this.isExtension = type == ExpressionType.EXTENSION;
        }
        
        // Getters
        public String getOriginalExpression() { return originalExpression; }
        public ExpressionType getType() { return type; }
        public List<String> getFieldPaths() { return fieldPaths; }
        public String getPrimaryFieldPath() { return fieldPaths.isEmpty() ? null : fieldPaths.get(0); }
        public String getExtensionUrl() { return extensionUrl; }
        public String getExtensionValueField() { return extensionValueField; }
        public boolean isUnion() { return isUnion; }
        public boolean isExtension() { return isExtension; }
        public boolean isSimpleField() { return type == ExpressionType.SIMPLE_FIELD; }
    }
    
    /**
     * Types of FHIRPath expressions we can handle
     */
    public enum ExpressionType {
        SIMPLE_FIELD,    // "Condition.assertedDate"
        UNION,           // "onset.as(dateTime) | Condition.onset.as(Period)"
        EXTENSION,       // "extension.where(url='...').valueDateTime"
        REFERENCE_WHERE, // "subject.where(resolve() is Patient)"
        UNKNOWN
    }
    
    /**
     * Main entry point - parse any FHIRPath expression
     */
    public static ParsedExpression parse(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            logger.warn("🔍 FHIRPathParser: Empty expression");
            return new ParsedExpression(expression, ExpressionType.UNKNOWN, null, null, null);
        }
        
        String trimmed = expression.trim();
        logger.debug("🔍 FHIRPathParser: Parsing expression: {}", trimmed);
        
        // Check for extension expressions first (most complex)
        if (trimmed.contains("extension.where")) {
            return parseExtensionExpression(trimmed);
        }
        
        // Check for reference where expressions
        if (trimmed.contains(".where(resolve()")) {
            return parseReferenceWhereExpression(trimmed);
        }
        
        // Check for union expressions (HAPI complex paths)
        if (trimmed.contains(" | ")) {
            return parseUnionExpression(trimmed);
        }
        
        // Default to simple field expression
        return parseSimpleFieldExpression(trimmed);
    }
    
    /**
     * Parse extension-based expressions for US Core parameters
     */
    private static ParsedExpression parseExtensionExpression(String expression) {
        logger.debug("🔍 FHIRPathParser: Parsing extension expression");
        
        String extensionUrl = extractExtensionUrl(expression);
        String valueField = extractExtensionValueField(expression);
        
        logger.debug("🔍 FHIRPathParser: Extension URL: {}, Value field: {}", extensionUrl, valueField);
        
        return new ParsedExpression(expression, ExpressionType.EXTENSION, 
                                  null, extensionUrl, valueField);
    }
    
    /**
     * Parse reference where expressions like "subject.where(resolve() is Patient)"
     */
    private static ParsedExpression parseReferenceWhereExpression(String expression) {
        logger.debug("🔍 FHIRPathParser: Parsing reference where expression");
        
        // Extract the base field before .where()
        String baseField = null;
        int whereIndex = expression.indexOf(".where(");
        if (whereIndex > 0) {
            String beforeWhere = expression.substring(0, whereIndex);
            baseField = extractSimpleFieldPath(beforeWhere);
        }
        
        if (baseField == null) {
            logger.warn("🔍 FHIRPathParser: Could not extract base field from reference where expression: {}", expression);
            baseField = "unknown";
        }
        
        logger.debug("🔍 FHIRPathParser: Reference base field: {}", baseField);
        
        List<String> fieldPaths = new ArrayList<>();
        fieldPaths.add(baseField);
        
        return new ParsedExpression(expression, ExpressionType.REFERENCE_WHERE, 
                                  fieldPaths, null, null);
    }
    
    /**
     * Parse union expressions like "onsetDateTime | onsetPeriod"
     */
    private static ParsedExpression parseUnionExpression(String expression) {
        logger.debug("🔍 FHIRPathParser: Parsing union expression");
        
        String[] alternatives = expression.split("\\s*\\|\\s*");
        List<String> fieldPaths = new ArrayList<>();
        
        for (String alternative : alternatives) {
            String fieldPath = parseAlternativeExpression(alternative.trim());
            if (fieldPath != null && !fieldPath.isEmpty()) {
                fieldPaths.add(fieldPath);
            }
        }
        
        logger.debug("🔍 FHIRPathParser: Union alternatives: {}", fieldPaths);
        
        return new ParsedExpression(expression, ExpressionType.UNION, 
                                  fieldPaths, null, null);
    }
    
    /**
     * Parse simple field expressions like "Condition.assertedDate"
     */
    private static ParsedExpression parseSimpleFieldExpression(String expression) {
        logger.debug("🔍 FHIRPathParser: Parsing simple field expression");
        
        String fieldPath = extractSimpleFieldPath(expression);
        if (fieldPath == null) {
            fieldPath = expression; // Fallback to original
        }
        
        logger.debug("🔍 FHIRPathParser: Simple field: {}", fieldPath);
        
        List<String> fieldPaths = new ArrayList<>();
        fieldPaths.add(fieldPath);
        
        return new ParsedExpression(expression, ExpressionType.SIMPLE_FIELD, 
                                  fieldPaths, null, null);
    }
    
    /**
     * Suggest field name for DATE parameters on choice types (fallback when HAPI introspection fails)
     */
    public static String suggestDateTimeFieldPath(String expression) {
        String fieldPath = extractSimpleFieldPath(expression);
        if (fieldPath == null) {
            return null;
        }
        
        // Check if this is a known choice type that should have DateTime suffix for DATE searches
        if (isKnownChoiceTypeForDateTime(fieldPath)) {
            String dateTimeField = fieldPath + "DateTime";
            logger.debug("🔍 FHIRPathParser: Suggesting DateTime field for choice type: {} -> {}", fieldPath, dateTimeField);
            return dateTimeField;
        }
        
        return fieldPath;
    }
    
    /**
     * Check if field is a known choice type that commonly has DateTime variant
     */
    private static boolean isKnownChoiceTypeForDateTime(String fieldName) {
        // Common FHIR choice types that have [x] suffix and DateTime variants
        return fieldName.equals("effective") ||     // DiagnosticReport.effective[x]
               fieldName.equals("onset") ||         // Condition.onset[x] 
               fieldName.equals("occurrence") ||    // Various resources with occurrence[x]
               fieldName.equals("performed") ||     // Procedure.performed[x]
               fieldName.equals("value") ||         // Observation.value[x]
               fieldName.equals("deceased") ||      // Patient.deceased[x] (though this is usually cast correctly)
               fieldName.equals("created");         // Various resources with created[x] (e.g., DocumentReference)
    }
    
    /**
     * Parse individual alternative in union expression or casting expression
     */
    private static String parseAlternativeExpression(String alternative) {
        // Handle casting expressions like "Resource.field.as(SomeType)" -> "fieldSomeType"
        if (alternative.contains(".as(") && alternative.endsWith(")")) {
            return parseCastingExpression(alternative);
        }
        
        // Handle simple field extraction
        return extractSimpleFieldPath(alternative);
    }
    
    /**
     * Parse casting expressions like "Goal.target.due.as(date)" -> "target.dueDate"
     * or "MedicationRequest.medication as Reference" -> "medicationReference"
     */
    private static String parseCastingExpression(String expression) {
        int asIndex = expression.indexOf(".as(");
        int closeParenIndex = expression.lastIndexOf(")");
        
        if (asIndex == -1 || closeParenIndex == -1) {
            return extractSimpleFieldPath(expression);
        }
        
        String pathPart = expression.substring(0, asIndex);
        String typePart = expression.substring(asIndex + 4, closeParenIndex); // +4 for ".as("
        
        // Capitalize first letter of type (e.g., "date" -> "Date", "Reference" -> "Reference")
        String capitalizedType = typePart.substring(0, 1).toUpperCase() + 
                               (typePart.length() > 1 ? typePart.substring(1) : "");
        
        if (pathPart.contains(".")) {
            String[] pathParts = pathPart.split("\\.");
            if (pathParts.length >= 2) {
                // Remove resource type (first part) and preserve the rest of the path
                String[] fieldParts = new String[pathParts.length - 1];
                System.arraycopy(pathParts, 1, fieldParts, 0, pathParts.length - 1);
                
                // Replace last field with field + capitalizedType
                String lastField = fieldParts[fieldParts.length - 1];
                fieldParts[fieldParts.length - 1] = lastField + capitalizedType;
                
                String result = String.join(".", fieldParts);
                logger.debug("🔍 FHIRPathParser: Converted cast expression: {} -> {}", expression, result);
                return result;
            }
        }
        
        // If no dots in path (shouldn't happen for FHIR paths, but handle gracefully)
        logger.debug("🔍 FHIRPathParser: Simple cast expression without dots: {} -> {}{}", expression, pathPart, capitalizedType);
        return pathPart + capitalizedType;
    }
    
    /**
     * Extract simple field path from expression like "Condition.assertedDate" -> "assertedDate"
     * Also handles parenthetical casting expressions like "(Patient.deceased as dateTime)" -> "deceasedDateTime"
     */
    private static String extractSimpleFieldPath(String expression) {
        // Handle parenthetical casting expressions like "(Patient.deceased as dateTime)"
        if (expression.startsWith("(") && expression.endsWith(")")) {
            String inner = expression.substring(1, expression.length() - 1);
            logger.info("🔍 FHIRPathParser: Handling parenthetical expression: {}", inner);
            
            // Handle "as Type" casting
            if (inner.contains(" as ")) {
                return parseCastingExpression(inner.replace(" as ", ".as(") + ")");
            }
            
            // Fallback to processing the inner expression
            return extractSimpleFieldPath(inner);
        }
        
        // Handle simple dot notation like "Resource.field" or "Resource.nested.field"
        if (expression.contains(".") && !expression.contains("(")) {
            String[] parts = expression.split("\\.");
            if (parts.length >= 2) {
                // Return everything after the resource type (e.g., CareTeam.participant.role -> participant.role)
                return String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            }
        }
        
        // If no dot or complex expression, return as-is (might be just a field name)
        return expression;
    }
    
    /**
     * Extract extension URL from where clause
     */
    private static String extractExtensionUrl(String expression) {
        if (expression.contains("where(url = '")) {
            int start = expression.indexOf("where(url = '") + "where(url = '".length();
            int end = expression.indexOf("')", start);
            if (end > start) {
                String url = expression.substring(start, end);
                logger.debug("🔍 FHIRPathParser: Extracted extension URL: {}", url);
                return url;
            }
        }
        return null;
    }
    
    /**
     * Extract extension value field from expression
     */
    private static String extractExtensionValueField(String expression) {
        if (expression.contains(".value")) {
            int valueIndex = expression.lastIndexOf(".value");
            String valuePart = expression.substring(valueIndex + 1);
            
            // Clean up any trailing conditions or parentheses
            if (valuePart.contains(" ") || valuePart.contains(")")) {
                valuePart = valuePart.split("[ )]")[0];
            }
            
            String field = "extension." + valuePart;
            logger.debug("🔍 FHIRPathParser: Extracted extension value field: {}", field);
            return field;
        }
        return "extension.value"; // Default fallback
    }
}