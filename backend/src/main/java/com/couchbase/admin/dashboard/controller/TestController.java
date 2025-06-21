package com.couchbase.admin.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Hello from Couchbase FHIR CE Backend!");
        response.put("timestamp", System.currentTimeMillis());
        response.put("version", "0.0.1-SNAPSHOT");
        return response;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "Couchbase FHIR CE Backend");
        response.put("status", "running");
        response.put("uptime", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        response.put("memory", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        return response;
    }

    @GetMapping("/admin")
    public Map<String, Object> adminInfo() {
        Map<String, Object> response = new HashMap<>();
        response.put("module", "admin");
        response.put("description", "Admin and dashboard services");
        response.put("endpoints", new String[]{"/api/dashboard/metrics", "/api/dashboard/health"});
        return response;
    }
}
