package com.couchbase.fhir.resources.interceptor;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for accessing and updating the RequestPerfBag from anywhere in the request processing flow.
 */
public class RequestPerfBagUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(RequestPerfBagUtils.class);
    private static final String UD_PERF_BAG = "_perf_bag";
    
    /**
     * Get the current request's PerfBag, or null if not available
     */
    public static RequestPerfBag getCurrentPerfBag(RequestDetails requestDetails) {
        if (requestDetails == null) {
            return null;
        }
        try {
            return (RequestPerfBag) requestDetails.getUserData().get(UD_PERF_BAG);
        } catch (Exception e) {
            logger.debug("Could not retrieve PerfBag from request: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Add a timing measurement to the current request's PerfBag
     * Safe to call even if PerfBag is not available
     */
    public static void addTiming(RequestDetails requestDetails, String name, long durationMs) {
        RequestPerfBag perfBag = getCurrentPerfBag(requestDetails);
        if (perfBag != null) {
            perfBag.addTiming(name, durationMs);
        }
    }
    
    /**
     * Add a count measurement to the current request's PerfBag
     * Safe to call even if PerfBag is not available
     */
    public static void addCount(RequestDetails requestDetails, String name, int count) {
        RequestPerfBag perfBag = getCurrentPerfBag(requestDetails);
        if (perfBag != null) {
            perfBag.addCount(name, count);
        }
    }
    
    /**
     * Increment a counter by 1 (convenience method)
     * Safe to call even if PerfBag is not available
     */
    public static void incrementCount(RequestDetails requestDetails, String name) {
        RequestPerfBag perfBag = getCurrentPerfBag(requestDetails);
        if (perfBag != null) {
            perfBag.incrementCount(name);
        }
    }
    
    /**
     * Get the current request ID for correlation
     */
    public static String getCurrentRequestId(RequestDetails requestDetails) {
        RequestPerfBag perfBag = getCurrentPerfBag(requestDetails);
        return perfBag != null ? perfBag.getRequestId() : "unknown";
    }
}
