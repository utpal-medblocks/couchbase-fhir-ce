package com.couchbase.fhir.resources.interceptor;

/**
 * ThreadLocal context for capturing detailed DAO timing breakdown
 */
public class DAOTimingContext {
    
    private static final ThreadLocal<DAOTimingContext> CONTEXT = new ThreadLocal<>();
    
    private long queryExecutionMs = 0;
    private long hapiParsingMs = 0;
    
    public static void start() {
        CONTEXT.set(new DAOTimingContext());
    }
    
    public static void recordQueryTime(long ms) {
        DAOTimingContext context = CONTEXT.get();
        if (context != null) {
            context.queryExecutionMs = ms;
        }
    }
    
    public static void recordParsingTime(long ms) {
        DAOTimingContext context = CONTEXT.get();
        if (context != null) {
            context.hapiParsingMs = ms;
        }
    }
    
    public static DAOTimingContext getAndClear() {
        DAOTimingContext context = CONTEXT.get();
        CONTEXT.remove();
        return context;
    }
    
    public long getQueryExecutionMs() { return queryExecutionMs; }
    public long getHapiParsingMs() { return hapiParsingMs; }
}
