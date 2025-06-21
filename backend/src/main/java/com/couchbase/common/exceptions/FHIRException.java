package com.couchbase.common.exceptions;

public class FHIRException extends RuntimeException {
    
    public FHIRException(String message) {
        super(message);
    }

    public FHIRException(String message, Throwable cause) {
        super(message, cause);
    }
} 
