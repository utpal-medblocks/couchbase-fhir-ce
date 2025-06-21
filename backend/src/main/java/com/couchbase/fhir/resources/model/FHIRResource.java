package com.couchbase.fhir.resources.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FHIRResource {
    private String id;
    private String resourceType;
    private String version;
    private LocalDateTime lastModified;
    private String tenant;
    private Map<String, Object> resource;
    private String status;
} 