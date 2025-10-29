package com.couchbase.fhir.resources.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class FastJsonBundleBuilder {
    
    private static final Logger logger = LoggerFactory.getLogger(FastJsonBundleBuilder.class);
    
    public String buildSearchsetBundle(
            List<String> primaryResourceJsonList,
            List<String> includedResourceJsonList,
            int totalPrimaries,
            String selfUrl,
            String nextUrl,
            String primaryResourceType,
            Instant timestamp) {
        
        long startMs = System.currentTimeMillis();
        
        int primaryCount = primaryResourceJsonList != null ? primaryResourceJsonList.size() : 0;
        int includedCount = includedResourceJsonList != null ? includedResourceJsonList.size() : 0;
        int totalEntries = primaryCount + includedCount;
        
        logger.debug("🚀 FASTPATH: Building Bundle with {} primaries + {} includes = {} total entries", 
                    primaryCount, includedCount, totalEntries);
        
        int estimatedSize = (totalEntries * 2048) + 512;
        StringBuilder json = new StringBuilder(estimatedSize);
        
        json.append("{")
            .append("\"resourceType\":\"Bundle\",")
            .append("\"type\":\"searchset\",")
            .append("\"total\":").append(totalPrimaries).append(",")
            .append("\"timestamp\":\"").append(timestamp.toString()).append("\",");
        
        json.append("\"link\":[");
        json.append("{\"relation\":\"self\",\"url\":\"").append(escapeJson(selfUrl)).append("\"}");
        if (nextUrl != null) {
            json.append(",{\"relation\":\"next\",\"url\":\"").append(escapeJson(nextUrl)).append("\"}");
        }
        json.append("],");
        
        json.append("\"entry\":[");
        
        if (primaryCount > 0) {
            for (int i = 0; i < primaryCount; i++) {
                String resourceJson = primaryResourceJsonList.get(i);
                json.append("{\"resource\":").append(resourceJson)
                    .append(",\"search\":{\"mode\":\"match\"}}");
                
                if (i < primaryCount - 1 || includedCount > 0) {
                    json.append(",");
                }
            }
        }
        
        if (includedCount > 0) {
            for (int i = 0; i < includedCount; i++) {
                String resourceJson = includedResourceJsonList.get(i);
                json.append("{\"resource\":").append(resourceJson)
                    .append(",\"search\":{\"mode\":\"include\"}}");
                
                if (i < includedCount - 1) {
                    json.append(",");
                }
            }
        }
        
        json.append("]}");
        
        long elapsedMs = System.currentTimeMillis() - startMs;
        logger.info("🚀 FASTPATH: Built Bundle in {} ms ({} bytes, {} entries)", 
                   elapsedMs, json.length(), totalEntries);
        
        return json.toString();
    }
    
    public String buildEmptySearchsetBundle(String selfUrl, Instant timestamp) {
        return """
            {
              "resourceType": "Bundle",
              "type": "searchset",
              "total": 0,
              "timestamp": "%s",
              "link": [
                {"relation": "self", "url": "%s"}
              ],
              "entry": []
            }
            """.formatted(timestamp.toString(), escapeJson(selfUrl));
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

