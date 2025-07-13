package com.couchbase.fhir.validation.model;

import lombok.Data;
import java.util.List;
import java.util.ArrayList;

@Data
public class ValidationResult {
    private boolean valid;
    private List<String> errors;
    private List<String> warnings;
    private String resourceType;
    private String resourceId;
    
    public ValidationResult() {
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }
} 