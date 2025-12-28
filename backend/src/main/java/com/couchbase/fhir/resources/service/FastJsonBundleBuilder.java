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
     * Build FHIR Bundle as UTF-8 bytes with ZERO-COPY optimization
     * Accepts raw byte[] JSON from Couchbase KV (no String conversion needed!)
     * 
     * Memory savings:
     * - No JsonObject creation (from Couchbase SDK)
     * - No String creation (UTF-16 → UTF-8)
     * - Just byte array copying (fastest possible)
     */
    public byte[] buildSearchsetBundle(
            Map<String, byte[]> primaryKeyToBytesMap,
            Map<String, byte[]> includedKeyToBytesMap,
            int totalPrimaries,
            String selfUrl,
            String nextUrl,
            String previousUrl,
            String baseUrl,
            Instant timestamp) {
        
        long startMs = System.currentTimeMillis();
        
        int primaryCount = primaryKeyToBytesMap != null ? primaryKeyToBytesMap.size() : 0;
        int includedCount = includedKeyToBytesMap != null ? includedKeyToBytesMap.size() : 0;
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
        
            // Add primary resources (ZERO-COPY: write raw bytes directly!)
            if (primaryCount > 0) {
                int i = 0;
                for (Map.Entry<String, byte[]> entry : primaryKeyToBytesMap.entrySet()) {
                    String key = entry.getKey();  // e.g., "Patient/example-targeted-provenance"
                    byte[] resourceBytes = entry.getValue();  // Raw JSON bytes from Couchbase!
                    String fullUrl = baseUrl + "/" + key;
                    
                    write(baos, "{\"fullUrl\":\"" + escapeJson(fullUrl) + "\",");
                    write(baos, "\"resource\":");
                    baos.write(resourceBytes);  // ZERO-COPY: Write bytes directly!
                    write(baos, ",\"search\":{\"mode\":\"match\"}}");
                    
                    if (i < primaryCount - 1 || includedCount > 0) {
                        write(baos, ",");
                    }
                    i++;
                }
            }
            
            // Add included resources (ZERO-COPY: write raw bytes directly!)
            if (includedCount > 0) {
                int i = 0;
                for (Map.Entry<String, byte[]> entry : includedKeyToBytesMap.entrySet()) {
                    String key = entry.getKey();  // e.g., "Practitioner/example"
                    byte[] resourceBytes = entry.getValue();  // Raw JSON bytes from Couchbase!
                    String fullUrl = baseUrl + "/" + key;
                    
                    write(baos, "{\"fullUrl\":\"" + escapeJson(fullUrl) + "\",");
                    write(baos, "\"resource\":");
                    baos.write(resourceBytes);  // ZERO-COPY: Write bytes directly!
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
            logger.debug("🚀 FASTPATH: Built Bundle in {} ms ({} bytes, {} entries)", 
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

