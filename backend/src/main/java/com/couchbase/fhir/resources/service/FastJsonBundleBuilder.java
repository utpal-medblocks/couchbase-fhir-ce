package com.couchbase.fhir.resources.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
public class FastJsonBundleBuilder {
    
    private static final Logger logger = LoggerFactory.getLogger(FastJsonBundleBuilder.class);
    
    public String buildSearchsetBundle(
            Map<String, String> primaryKeyToJsonMap,
            Map<String, String> includedKeyToJsonMap,
            int totalPrimaries,
            String selfUrl,
            String nextUrl,
            String previousUrl,
            String baseUrl,
            Instant timestamp) {
        
        long startMs = System.currentTimeMillis();
        
        int primaryCount = primaryKeyToJsonMap != null ? primaryKeyToJsonMap.size() : 0;
        int includedCount = includedKeyToJsonMap != null ? includedKeyToJsonMap.size() : 0;
        int totalEntries = primaryCount + includedCount;
        
        logger.debug("🚀 FASTPATH: Building Bundle with {} primaries + {} includes = {} total entries", 
                    primaryCount, includedCount, totalEntries);
        
        int estimatedSize = (totalEntries * 2048) + 512;
        StringBuilder json = new StringBuilder(estimatedSize);
        
        // Generate Bundle ID and format timestamp
        String bundleId = UUID.randomUUID().toString();
        String formattedTimestamp = timestamp.atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        json.append("{")
            .append("\"resourceType\":\"Bundle\",")
            .append("\"id\":\"").append(bundleId).append("\",")
            .append("\"meta\":{\"lastUpdated\":\"").append(formattedTimestamp).append("\"},")
            .append("\"type\":\"searchset\",")
            .append("\"total\":").append(totalPrimaries).append(",");
        
        // Build links in order: self, next, previous (FHIR standard)
        json.append("\"link\":[");
        json.append("{\"relation\":\"self\",\"url\":\"").append(escapeJson(selfUrl)).append("\"}");
        
        if (nextUrl != null) {
            json.append(",{\"relation\":\"next\",\"url\":\"").append(escapeJson(nextUrl)).append("\"}");
        }
        
        if (previousUrl != null) {
            json.append(",{\"relation\":\"previous\",\"url\":\"").append(escapeJson(previousUrl)).append("\"}");
        }
        
        json.append("],");
        
        json.append("\"entry\":[");
        
        // Add primary resources (use keys for fullUrl - no parsing needed!)
        if (primaryCount > 0) {
            int i = 0;
            for (Map.Entry<String, String> entry : primaryKeyToJsonMap.entrySet()) {
                String key = entry.getKey();  // e.g., "Patient/example-targeted-provenance"
                String resourceJson = entry.getValue();
                String fullUrl = baseUrl + "/" + key;
                
                json.append("{\"fullUrl\":\"").append(escapeJson(fullUrl)).append("\",")
                    .append("\"resource\":").append(resourceJson)
                    .append(",\"search\":{\"mode\":\"match\"}}");
                
                if (i < primaryCount - 1 || includedCount > 0) {
                    json.append(",");
                }
                i++;
            }
        }
        
        // Add included resources (use keys for fullUrl)
        if (includedCount > 0) {
            int i = 0;
            for (Map.Entry<String, String> entry : includedKeyToJsonMap.entrySet()) {
                String key = entry.getKey();  // e.g., "Practitioner/example"
                String resourceJson = entry.getValue();
                String fullUrl = baseUrl + "/" + key;
                
                json.append("{\"fullUrl\":\"").append(escapeJson(fullUrl)).append("\",")
                    .append("\"resource\":").append(resourceJson)
                    .append(",\"search\":{\"mode\":\"include\"}}");
                
                if (i < includedCount - 1) {
                    json.append(",");
                }
                i++;
            }
        }
        
        json.append("]}");
        
        long elapsedMs = System.currentTimeMillis() - startMs;
        logger.info("🚀 FASTPATH: Built Bundle in {} ms ({} bytes, {} entries)", 
                   elapsedMs, json.length(), totalEntries);
        
        return json.toString();
    }
    
    public String buildEmptySearchsetBundle(String selfUrl, Instant timestamp) {
        String bundleId = UUID.randomUUID().toString();
        String formattedTimestamp = timestamp.atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        return """
            {
              "resourceType": "Bundle",
              "id": "%s",
              "meta": {"lastUpdated": "%s"},
              "type": "searchset",
              "total": 0,
              "link": [
                {"relation": "self", "url": "%s"}
              ],
              "entry": []
            }
            """.formatted(bundleId, formattedTimestamp, escapeJson(selfUrl));
    }
    
    private String escapeJson(String input) {
        if (input == null) return "";
        
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}

