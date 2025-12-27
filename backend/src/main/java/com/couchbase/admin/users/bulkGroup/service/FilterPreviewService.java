package com.couchbase.admin.users.bulkGroup.service;

import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.fhir.resources.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to execute FHIR search filters and return preview results.
 * Uses SearchService directly - same code path as external FHIR clients (HAPI + FTS).
 * Supports multiple resource types for Group membership:
 * Device, Group, Medication, Patient, Practitioner, PractitionerRole, RelatedPerson, Substance
 */
@Service
public class FilterPreviewService {

    private static final Logger logger = LoggerFactory.getLogger(FilterPreviewService.class);
    private static final int MAX_PREVIEW_RESULTS = 10;

    // Resource types that can be group members (per FHIR spec)
    private static final Set<String> SUPPORTED_RESOURCE_TYPES = Set.of(
            "Device", "Group", "Medication", "Patient", "Practitioner",
            "PractitionerRole", "RelatedPerson", "Substance"
    );

    private final SearchService searchService;
    private final ConnectionService connectionService;

    public FilterPreviewService(SearchService searchService, ConnectionService connectionService) {
        this.searchService = searchService;
        this.connectionService = connectionService;
        logger.info("✅ FilterPreviewService initialized");
    }

    /**
     * Preview result containing total count and sample resources
     */
    public static class FilterPreviewResult {
        private final long totalCount;
        private final List<Map<String, Object>> sampleResources;
        private final String resourceType;
        private final String filter;

        public FilterPreviewResult(long totalCount, List<Map<String, Object>> sampleResources, 
                                   String resourceType, String filter) {
            this.totalCount = totalCount;
            this.sampleResources = sampleResources;
            this.resourceType = resourceType;
            this.filter = filter;
        }

        public long getTotalCount() { return totalCount; }
        public List<Map<String, Object>> getSampleResources() { return sampleResources; }
        public String getResourceType() { return resourceType; }
        public String getFilter() { return filter; }
    }

    /**
     * Execute a FHIR search filter and return preview (count + sample)
     * Uses SearchService directly - same code path as external clients (HAPI + FTS)
     * 
     * @param resourceType The FHIR resource type (Patient, Practitioner, etc.)
     * @param filterQuery FHIR search parameters (e.g., "family=Smith&birthdate=ge1987-01-01")
     * @return FilterPreviewResult with total count and up to 10 sample resources
     */
    public FilterPreviewResult executeFilterPreview(String resourceType, String filterQuery) {
        if (!SUPPORTED_RESOURCE_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException(
                    "Unsupported resource type for Group membership: " + resourceType + 
                    ". Supported types: " + SUPPORTED_RESOURCE_TYPES);
        }

        try {
            logger.info("🔍 Executing FHIR search via SearchService: {}?{}", resourceType, filterQuery);

            Map<String, List<String>> params = parseQueryParams(filterQuery);
            
            // Use searchForKeys() to get keys + total count (NO full resource fetch!)
            Map<String, List<String>> sampleParams = new HashMap<>(params);
            sampleParams.put("_count", List.of(String.valueOf(MAX_PREVIEW_RESULTS)));
            ServletRequestDetails sampleRequest = createRequestDetails(resourceType, sampleParams);
            
            SearchService.KeySearchResult keyResult = searchService.searchForKeys(resourceType, sampleRequest);
            
            int totalCount = (int) keyResult.getTotalCount();
            List<String> sampleKeys = keyResult.getKeys();

            // Use N1QL to fetch only display fields
            List<Map<String, Object>> sampleResources = fetchDisplayFields(resourceType, sampleKeys);

            logger.info("✅ FHIR search complete: {} total, {} samples", totalCount, sampleResources.size());
            return new FilterPreviewResult(totalCount, sampleResources, resourceType, filterQuery);

        } catch (Exception e) {
            logger.error("❌ Error executing FHIR search", e);
            throw new RuntimeException("Failed to execute filter: " + e.getMessage(), e);
        }
    }

    /**
     * Get all member IDs matching a filter (for group creation/refresh)
     * Uses SearchService with pagination to fetch all results
     * 
     * @param resourceType The FHIR resource type
     * @param filterQuery FHIR search parameters
     * @param maxMembers Maximum number of members to return (e.g., 10000)
     * @return List of resource IDs (e.g., ["Patient/123", "Patient/456"])
     */
    public List<String> getAllMatchingIds(String resourceType, String filterQuery, int maxMembers) {
        if (!SUPPORTED_RESOURCE_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException("Unsupported resource type: " + resourceType);
        }

        try {
            logger.info("🔍 Fetching all matching IDs for {}?{} (max {})", resourceType, filterQuery, maxMembers);

            Map<String, List<String>> params = parseQueryParams(filterQuery);
            params.put("_count", List.of(String.valueOf(Math.min(maxMembers, 10000)))); // Cap at 10k per request

            ServletRequestDetails request = createRequestDetails(resourceType, params);
            
            // Use searchForKeys() to get keys directly (NO full resource fetch!)
            SearchService.KeySearchResult keyResult = searchService.searchForKeys(resourceType, request);
            List<String> ids = keyResult.getKeys();

            logger.info("✅ Found {} matching resource IDs", ids.size());
            return ids;

        } catch (Exception e) {
            logger.error("❌ Error fetching matching IDs", e);
            throw new RuntimeException("Failed to fetch matching IDs: " + e.getMessage(), e);
        }
    }

    /**
     * Create ServletRequestDetails from search parameters
     */
    private ServletRequestDetails createRequestDetails(String resourceType, Map<String, List<String>> params) {
        // Convert Map<String, List<String>> to Map<String, String[]>
        Map<String, String[]> searchParams = params.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().toArray(new String[0])
                ));
        
        ServletRequestDetails requestDetails = new ServletRequestDetails();
        requestDetails.setParameters(searchParams);
        return requestDetails;
    }

    /**
     * Parse query string into parameter map
     * HAPI requires Map<String, List<String>> for search parameters
     */
    private Map<String, List<String>> parseQueryParams(String query) {
        Map<String, List<String>> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }

        // Remove leading '?' if present
        String cleaned = query.startsWith("?") ? query.substring(1) : query;

        for (String pair : cleaned.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    String key = kv[0];
                    String value = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                } catch (Exception e) {
                    params.computeIfAbsent(kv[0], k -> new ArrayList<>()).add(kv[1]);
                }
            }
        }

        return params;
    }

    /**
     * Fetch only display fields using N1QL with USE KEYS for efficiency.
     * This avoids fetching full FHIR resources when we only need a preview.
     */
    private List<Map<String, Object>> fetchDisplayFields(String resourceType, List<String> keys) {
        if (keys.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            Cluster cluster = connectionService.getConnection("default");
            String bucketName = "fhir";
            String collectionPath = bucketName + ".Resources." + resourceType;

            // Build N1QL query with USE KEYS for efficient lookup
            StringBuilder query = new StringBuilder();
            query.append("SELECT META().id as id, ");
            
            // Resource-specific field extraction
            switch (resourceType) {
                case "Patient":
                    query.append("name[0].given[0] as given, ")
                         .append("name[0].family as family, ")
                         .append("birthDate, gender ");
                    break;
                case "Practitioner":
                case "RelatedPerson":
                    query.append("name[0].given[0] as given, ")
                         .append("name[0].family as family, ")
                         .append("NULL as birthDate, ")
                         .append("gender ");
                    break;
                case "Device":
                    query.append("deviceName[0].name as deviceName, ")
                         .append("type.coding[0].display as deviceType, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "Medication":
                    query.append("code.coding[0].display as medicationName, ")
                         .append("form.coding[0].display as form, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "Substance":
                    query.append("code.coding[0].display as substanceName, ")
                         .append("category[0].coding[0].display as category, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "Group":
                    query.append("name as groupName, ")
                         .append("quantity, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "PractitionerRole":
                    query.append("practitioner.display as practitioner, ")
                         .append("code[0].coding[0].display as role, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                default:
                    query.append("NULL as given, NULL as family, NULL as birthDate, NULL as gender ");
            }
            
            query.append("FROM ").append(collectionPath).append(" USE KEYS [");
            
            // Add keys as JSON array elements
            for (int i = 0; i < keys.size(); i++) {
                query.append("'").append(keys.get(i)).append("'");
                if (i < keys.size() - 1) {
                    query.append(", ");
                }
            }
            query.append("]");

            logger.debug("🔍 N1QL Preview Query: {}", query.toString());

            com.couchbase.client.java.query.QueryResult result = cluster.query(query.toString());
            
            List<Map<String, Object>> displayData = new ArrayList<>();
            for (com.couchbase.client.java.json.JsonObject row : result.rowsAsObject()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.getString("id"));
                
                // Format display based on resource type
                if ("Patient".equals(resourceType) || "Practitioner".equals(resourceType) || "RelatedPerson".equals(resourceType)) {
                    String given = row.getString("given");
                    String family = row.getString("family");
                    item.put("name", (given != null ? given + " " : "") + (family != null ? family : ""));
                    item.put("birthDate", row.getString("birthDate"));
                    item.put("gender", row.getString("gender"));
                } else if ("Device".equals(resourceType)) {
                    item.put("name", row.getString("deviceName"));
                    item.put("type", row.getString("deviceType"));
                } else if ("Medication".equals(resourceType)) {
                    item.put("name", row.getString("medicationName"));
                    item.put("form", row.getString("form"));
                } else if ("Substance".equals(resourceType)) {
                    item.put("name", row.getString("substanceName"));
                    item.put("category", row.getString("category"));
                } else if ("Group".equals(resourceType)) {
                    item.put("name", row.getString("groupName"));
                    item.put("quantity", row.getInt("quantity"));
                } else if ("PractitionerRole".equals(resourceType)) {
                    item.put("practitioner", row.getString("practitioner"));
                    item.put("role", row.getString("role"));
                }
                
                displayData.add(item);
            }

            logger.debug("✅ Fetched {} display records via N1QL", displayData.size());
            return displayData;

        } catch (Exception e) {
            logger.error("❌ Error fetching display fields via N1QL", e);
            throw new RuntimeException("Failed to fetch display fields: " + e.getMessage(), e);
        }
    }
}
