package com.couchbase.fhir.resources.config;

/**
 * Single-tenant mode: Always returns "fhir" as the bucket name.
 * This replaces the previous multi-tenant ThreadLocal approach.
 */
public class TenantContextHolder {
    private static final String FIXED_BUCKET_NAME = "fhir";
    
    /**
     * Returns the fixed bucket name for single-tenant mode.
     * @return Always returns "fhir"
     */
    public static String getTenantId() {
        return FIXED_BUCKET_NAME;
    }

    // Legacy methods kept for compatibility but do nothing
    @Deprecated
    public static void setTenantId(String tenantId) {
        // No-op in single-tenant mode
    }

    @Deprecated
    public static void clear() {
        // No-op in single-tenant mode
    }
}
