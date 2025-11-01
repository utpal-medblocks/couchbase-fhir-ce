package com.couchbase.fhir.resources.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
public class FastJsonBundleBuilder {
    
    private static final Logger logger = LoggerFactory.getLogger(FastJsonBundleBuilder.class);
    
    /**
     * Build FHIR Bundle as UTF-8 bytes (not String) for 2x memory savings
     * Uses ByteArrayOutputStream instead of StringBuilder to avoid UTF-16 overhead
     */
    public byte[] buildSearchsetBundle(
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream(estimatedSize);
        
        // Generate Bundle ID and format timestamp
        String bundleId = UUID.randomUUID().toString();
        String formattedTimestamp = timestamp.atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        try {
            // Build Bundle header
            write(baos, "{");
            write(baos, "\"resourceType\":\"Bundle\",");
            write(baos, "\"id\":\"" + bundleId + "\",");
            write(baos, "\"meta\":{\"lastUpdated\":\"" + formattedTimestamp + "\"},");
            write(baos, "\"type\":\"searchset\",");
            write(baos, "\"total\":" + totalPrimaries + ",");
            
            // Build links in order: self, next, previous (FHIR standard)
            write(baos, "\"link\":[");
            write(baos, "{\"relation\":\"self\",\"url\":\"" + escapeJson(selfUrl) + "\"}");
            
            if (nextUrl != null) {
                write(baos, ",{\"relation\":\"next\",\"url\":\"" + escapeJson(nextUrl) + "\"}");
            }
            
            if (previousUrl != null) {
                write(baos, ",{\"relation\":\"previous\",\"url\":\"" + escapeJson(previousUrl) + "\"}");
            }
            
            write(baos, "],");
            
            write(baos, "\"entry\":[");
        
            // Add primary resources (use keys for fullUrl - no parsing needed!)
            if (primaryCount > 0) {
                int i = 0;
                for (Map.Entry<String, String> entry : primaryKeyToJsonMap.entrySet()) {
                    String key = entry.getKey();  // e.g., "Patient/example-targeted-provenance"
                    String resourceJson = entry.getValue();
                    String fullUrl = baseUrl + "/" + key;
                    
                    write(baos, "{\"fullUrl\":\"" + escapeJson(fullUrl) + "\",");
                    write(baos, "\"resource\":");
                    write(baos, resourceJson);
                    write(baos, ",\"search\":{\"mode\":\"match\"}}");
                    
                    if (i < primaryCount - 1 || includedCount > 0) {
                        write(baos, ",");
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
                    
                    write(baos, "{\"fullUrl\":\"" + escapeJson(fullUrl) + "\",");
                    write(baos, "\"resource\":");
                    write(baos, resourceJson);
                    write(baos, ",\"search\":{\"mode\":\"include\"}}");
                    
                    if (i < includedCount - 1) {
                        write(baos, ",");
                    }
                    i++;
                }
            }
            
            write(baos, "]}");
            
            byte[] result = baos.toByteArray();
            
            long elapsedMs = System.currentTimeMillis() - startMs;
            logger.info("🚀 FASTPATH: Built Bundle in {} ms ({} bytes, {} entries)", 
                       elapsedMs, result.length, totalEntries);
            
            return result;
            
        } catch (IOException e) {
            // Should never happen with ByteArrayOutputStream
            logger.error("❌ Failed to build Bundle JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to build Bundle JSON", e);
        }
    }
    
    public byte[] buildEmptySearchsetBundle(String selfUrl, Instant timestamp) {
        String bundleId = UUID.randomUUID().toString();
        String formattedTimestamp = timestamp.atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        String json = """
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
        
        return json.getBytes(StandardCharsets.UTF_8);
    }
    
    /**
     * Write string to ByteArrayOutputStream as UTF-8 bytes
     */
    private void write(ByteArrayOutputStream baos, String str) throws IOException {
        baos.write(str.getBytes(StandardCharsets.UTF_8));
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

