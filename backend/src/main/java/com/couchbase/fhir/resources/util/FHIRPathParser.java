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
        logger.info("🔍 FHIRPathParser: Parsing expression: {}", trimmed);
        
        // Check for extension expressions first (most complex)
        if (isExtensionExpression(trimmed)) {
            return parseExtensionExpression(trimmed);
        }
        
        // Check for reference where expressions
        if (isReferenceWhereExpression(trimmed)) {
            return parseReferenceWhereExpression(trimmed);
        }
        
        // Check for union expressions (HAPI complex paths)
        if (isUnionExpression(trimmed)) {
            return parseUnionExpression(trimmed);
        }
        
        // Default to simple field expression
        return parseSimpleFieldExpression(trimmed);
    }
    
    /**
     * Check if expression is an extension-based expression
     */
    private static boolean isExtensionExpression(String expression) {
        return expression.contains("extension.where");
    }
    
    /**
     * Check if expression is a reference where expression
     */
    private static boolean isReferenceWhereExpression(String expression) {
        return expression.contains(".where(resolve()") || expression.contains(".where(");
    }
    
    /**
     * Check if expression is a union expression (contains |)
     */
    private static boolean isUnionExpression(String expression) {
        return expression.contains(" | ");
    }
    
    /**
     * Parse extension expressions like "Condition.extension.where(url='...').valueDateTime"
     */
    private static ParsedExpression parseExtensionExpression(String expression) {
        logger.info("🔍 FHIRPathParser: Parsing extension expression");
        
        String extensionUrl = extractExtensionUrl(expression);
        String valueField = extractExtensionValueField(expression);
        
        List<String> fieldPaths = new ArrayList<>();
        if (valueField != null) {
            fieldPaths.add(valueField);
        }
        
        logger.info("🔍 FHIRPathParser: Extension URL: {}, Value field: {}", extensionUrl, valueField);
        
        return new ParsedExpression(expression, ExpressionType.EXTENSION, fieldPaths, extensionUrl, valueField);
    }
    
    /**
     * Parse reference where expressions like "subject.where(resolve() is Patient)"
     */
    private static ParsedExpression parseReferenceWhereExpression(String expression) {
        logger.info("🔍 FHIRPathParser: Parsing reference where expression");
        
        // Extract the base field before .where()
        String baseField = expression;
        int whereIndex = expression.indexOf(".where(");
        if (whereIndex != -1) {
            baseField = expression.substring(0, whereIndex);
            
            // Remove resource type prefix if present
            if (baseField.contains(".")) {
                String[] parts = baseField.split("\\.");
                if (parts.length >= 2) {
                    baseField = parts[1]; // Take the field after resource type
                }
            }
        }
        
        List<String> fieldPaths = new ArrayList<>();
        fieldPaths.add(baseField);
        
        logger.info("🔍 FHIRPathParser: Reference base field: {}", baseField);
        
        return new ParsedExpression(expression, ExpressionType.REFERENCE_WHERE, fieldPaths, null, null);
    }
    
    /**
     * Parse union expressions like "onset.as(dateTime) | Condition.onset.as(Period)"
     */
    private static ParsedExpression parseUnionExpression(String expression) {
        logger.info("🔍 FHIRPathParser: Parsing union expression");
        
        String[] alternatives = expression.split(" \\| ");
        List<String> fieldPaths = new ArrayList<>();
        
        for (String alternative : alternatives) {
            String fieldPath = parseAlternative(alternative.trim());
            if (fieldPath != null && !fieldPaths.contains(fieldPath)) {
                fieldPaths.add(fieldPath);
            }
        }
        
        logger.info("🔍 FHIRPathParser: Union alternatives: {}", fieldPaths);
        
        return new ParsedExpression(expression, ExpressionType.UNION, fieldPaths, null, null);
    }
    
    /**
     * Parse simple field expressions like "Condition.assertedDate"
     */
    private static ParsedExpression parseSimpleFieldExpression(String expression) {
        logger.info("🔍 FHIRPathParser: Parsing simple field expression");
        
        String fieldPath = extractSimpleFieldPath(expression);
        List<String> fieldPaths = new ArrayList<>();
        if (fieldPath != null) {
            fieldPaths.add(fieldPath);
        }
        
        logger.info("🔍 FHIRPathParser: Simple field: {}", fieldPath);
        
        return new ParsedExpression(expression, ExpressionType.SIMPLE_FIELD, fieldPaths, null, null);
    }
    
    /**
     * Suggest field name for DATE parameters on choice types
     * For expressions like "DiagnosticReport.effective", suggests "effectiveDateTime"
     */
    public static String suggestDateTimeFieldPath(String expression) {
        String fieldPath = extractSimpleFieldPath(expression);
        if (fieldPath == null) {
            return null;
        }
        
        // Check if this is a known choice type that should have DateTime suffix for DATE searches
        if (isKnownChoiceTypeForDateTime(fieldPath)) {
            String dateTimeField = fieldPath + "DateTime";
            logger.info("🔍 FHIRPathParser: Suggesting DateTime field for choice type: {} -> {}", fieldPath, dateTimeField);
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
     * Parse individual alternative in union expression
     */
    private static String parseAlternative(String alternative) {
        // Handle type casting: "onset.as(dateTime)" -> "onsetDateTime"
        if (alternative.contains(".as(dateTime)")) {
            String withoutCast = alternative.replace(".as(dateTime)", "");
            String baseField = extractSimpleFieldPath(withoutCast);
            String dateTimeField = baseField + "DateTime";
            logger.info("🔍 FHIRPathParser: dateTime alternative: {} -> {}", alternative, dateTimeField);
            return dateTimeField;
        }
        
        // Handle Period type: "Condition.onset.as(Period)" -> "onsetPeriod"
        if (alternative.contains(".as(Period)")) {
            String withoutCast = alternative.replace(".as(Period)", "");
            String baseField = extractSimpleFieldPath(withoutCast);
            String periodField = baseField + "Period";
            logger.info("🔍 FHIRPathParser: Period alternative: {} -> {}", alternative, periodField);
            return periodField;
        }
        
        // Default to simple field extraction
        return extractSimpleFieldPath(alternative);
    }
    
    /**
     * Extract simple field path from expression like "Condition.assertedDate" -> "assertedDate"
     * Also handles casting expressions like "(Patient.deceased as dateTime)" -> "deceasedDateTime"
     * Detects choice types and suggests appropriate suffixes for DATE parameters
     */
    private static String extractSimpleFieldPath(String expression) {
        // Handle parenthetical casting expressions like "(Patient.deceased as dateTime)"
        if (expression.startsWith("(") && expression.endsWith(")")) {
            String inner = expression.substring(1, expression.length() - 1);
            logger.info("🔍 FHIRPathParser: Handling parenthetical expression: {}", inner);
            
            // Handle "as dateTime" casting
            if (inner.contains(" as dateTime")) {
                String withoutCast = inner.replace(" as dateTime", "");
                if (withoutCast.contains(".")) {
                    String[] parts = withoutCast.split("\\.");
                    if (parts.length >= 2) {
                        String fieldName = parts[1] + "DateTime";
                        logger.info("🔍 FHIRPathParser: Converted cast expression to: {}", fieldName);
                        return fieldName;
                    }
                }
            }
            
            // Handle "as Period" casting
            if (inner.contains(" as Period")) {
                String withoutCast = inner.replace(" as Period", "");
                if (withoutCast.contains(".")) {
                    String[] parts = withoutCast.split("\\.");
                    if (parts.length >= 2) {
                        String fieldName = parts[1] + "Period";
                        logger.info("🔍 FHIRPathParser: Converted cast expression to: {}", fieldName);
                        return fieldName;
                    }
                }
            }
            
            // Fallback to processing the inner expression
            return extractSimpleFieldPath(inner);
        }
        
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
