package com.couchbase.fhir.resources.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/fhir-test")
public class FHIRTestController {

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> response = new HashMap<>();
        response.put("module", "fhir");
        response.put("description", "FHIR API services");
        response.put("endpoints", new String[]{"/fhir/{tenant}/{resourceType}", "/fhir/{tenant}/{resourceType}/{id}"});
        response.put("version", "R4");
        return response;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> response = new HashMap<>();
        response.put("fhirVersion", "4.0.1");
        response.put("supportedResources", new String[]{"Patient", "Observation", "Encounter", "Condition", "Procedure", "MedicationRequest"});
        response.put("interactions", new String[]{"read", "create", "search-type"});
        response.put("multiTenant", true);
        return response;
    }
}
