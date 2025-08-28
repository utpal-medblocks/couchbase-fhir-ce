package com.couchbase.fhir.resources.search;

/**
 * Represents a parsed _revinclude parameter.
 * Format: ResourceType:searchParam
 * Example: "Observation:subject" means find Observations where subject references the primary resource
 */
public class RevIncludeParam {
    
    private final String resourceType;
    private final String searchParam;
    private final String originalValue;
    
    public RevIncludeParam(String resourceType, String searchParam, String originalValue) {
        this.resourceType = resourceType;
        this.searchParam = searchParam;
        this.originalValue = originalValue;
    }
    
    /**
     * Parse a _revinclude parameter string
     * 
     * @param revIncludeValue The _revinclude parameter value (e.g., "Observation:subject")
     * @return Parsed RevIncludeParam or null if invalid format
     */
    public static RevIncludeParam parse(String revIncludeValue) {
        if (revIncludeValue == null || revIncludeValue.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = revIncludeValue.trim();
        int colonIndex = trimmed.indexOf(':');
        
        if (colonIndex == -1 || colonIndex == 0 || colonIndex == trimmed.length() - 1) {
            // Invalid format - no colon, or colon at start/end
            return null;
        }
        
        String resourceType = trimmed.substring(0, colonIndex).trim();
        String searchParam = trimmed.substring(colonIndex + 1).trim();
        
        if (resourceType.isEmpty() || searchParam.isEmpty()) {
            return null;
        }
        
        return new RevIncludeParam(resourceType, searchParam, revIncludeValue);
    }
    
    /**
     * Check if this revinclude param is valid
     * 
     * @return true if both resourceType and searchParam are non-empty
     */
    public boolean isValid() {
        return resourceType != null && !resourceType.isEmpty() &&
               searchParam != null && !searchParam.isEmpty();
    }
    
    // Getters
    public String getResourceType() {
        return resourceType;
    }
    
    public String getSearchParam() {
        return searchParam;
    }
    
    public String getOriginalValue() {
        return originalValue;
    }
    
    @Override
    public String toString() {
        return "RevIncludeParam{" +
                "resourceType='" + resourceType + '\'' +
                ", searchParam='" + searchParam + '\'' +
                ", originalValue='" + originalValue + '\'' +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        RevIncludeParam that = (RevIncludeParam) o;
        
        if (!resourceType.equals(that.resourceType)) return false;
        return searchParam.equals(that.searchParam);
    }
    
    @Override
    public int hashCode() {
        int result = resourceType.hashCode();
        result = 31 * result + searchParam.hashCode();
        return result;
    }
}
