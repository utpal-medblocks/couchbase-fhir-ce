package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.sort.SearchSort;
import com.couchbase.common.config.FhirResourceMappingConfig;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for handling FHIR $everything operation on Patient resources.
 * 
 * <p>The $everything operation returns all resources related to a specific patient, including:
 * - The patient resource itself
 * - All resources that reference the patient (Observation, Condition, etc.)
 * - Supports filtering by date ranges, resource types, and pagination
 * 
 * <p>Strategy:
 * 1. Validate patient exists via KV lookup
 * 2. Search across all resource types that can reference a patient
 * 3. Use FTS queries with subject.reference OR patient.reference filters
 * 4. Group results by resource type and batch fetch via KV
 * 5. Support pagination using the same strategy as _revinclude
 */
@Service
public class EverythingService {
    
    private static final Logger logger = LoggerFactory.getLogger(EverythingService.class);
    
    private final CollectionRoutingService collectionRoutingService;
    private final FtsSearchService ftsSearchService;
    private final BatchKvService batchKvService;
    private final FhirResourceMappingConfig mappingConfig;
    private final com.couchbase.fhir.resources.search.SearchStateManager searchStateManager;
    
    /**
     * Collections to exclude from $everything operation
     * - Versions: Historical versions (separate operation)
     * - Tombstones: Deleted resources
     */
    private static final Set<String> EXCLUDED_COLLECTIONS = Set.of("Versions", "Tombstones");
    
    public EverythingService(
            CollectionRoutingService collectionRoutingService,
            FtsSearchService ftsSearchService,
            BatchKvService batchKvService,
            FhirResourceMappingConfig mappingConfig,
            com.couchbase.fhir.resources.search.SearchStateManager searchStateManager) {
        this.collectionRoutingService = collectionRoutingService;
        this.ftsSearchService = ftsSearchService;
        this.batchKvService = batchKvService;
        this.mappingConfig = mappingConfig;
        this.searchStateManager = searchStateManager;
    }
    
    /**
     * Result holder for $everything operation
     */
    public static class EverythingResult {
        public final Patient patient;
        public final List<Resource> firstPageResources;
        public final List<String> allDocumentKeys;
        public final int totalResourceCount;
        public final boolean needsPagination;
        
        public EverythingResult(Patient patient, List<Resource> firstPageResources, 
                               List<String> allDocumentKeys, int totalResourceCount, boolean needsPagination) {
            this.patient = patient;
            this.firstPageResources = firstPageResources;
            this.allDocumentKeys = allDocumentKeys;
            this.totalResourceCount = totalResourceCount;
            this.needsPagination = needsPagination;
        }
    }
    
    /**
     * Get all resources related to a patient (with pagination support)
     * 
     * @param patientId The patient ID
     * @param start Start date filter (optional)
     * @param end End date filter (optional)
     * @param types Resource type filter (optional, comma-separated)
     * @param since Only resources updated after this instant (optional)
     * @param count Page size (default: 50, max: 200)
     * @param baseUrl Base URL for building links
     * @return EverythingResult with patient, first page, and pagination info
     */
    public EverythingResult getPatientEverything(
            String patientId,
            Date start,
            Date end,
            String types,
            Date since,
            Integer count,
            String baseUrl) {
        
        String bucketName = TenantContextHolder.getTenantId();
        logger.info("🌍 $everything for Patient/{} (bucket: {}, start: {}, end: {}, types: {}, since: {}, count: {})", 
                   patientId, bucketName, start, end, types, since, count);
        
        // Step 1: Validate patient exists and get the resource
        Patient patient = getPatientResource(patientId, bucketName);
        
        // Step 2: Determine which collections to search
        List<String> collectionsToSearch = determineCollections(types);
        logger.info("🌍 Searching {} collections for Patient/{}", collectionsToSearch.size(), patientId);
        
        // Step 3: Search for all related resource KEYS across all collections (don't fetch yet!)
        List<String> allDocumentKeys = searchRelatedResourceKeys(
            patientId, 
            collectionsToSearch, 
            start, 
            end, 
            since, 
            bucketName
        );
        
        // Step 4: Determine pagination
        int effectiveCount = (count != null && count > 0) ? Math.min(count, 200) : 50;
        boolean needsPagination = allDocumentKeys.size() > effectiveCount;
        
        // Step 5: Fetch first page of resources
        int firstPageSize = Math.min(effectiveCount, allDocumentKeys.size());
        List<String> firstPageKeys = allDocumentKeys.subList(0, firstPageSize);
        List<Resource> firstPageResources = fetchResourcesByKeys(firstPageKeys, bucketName);
        
        logger.info("✅ $everything found {} total resources (1 Patient + {} related), returning first {} resources", 
                   allDocumentKeys.size() + 1, allDocumentKeys.size(), firstPageResources.size() + 1);
        
        return new EverythingResult(
            patient,
            firstPageResources,
            allDocumentKeys,
            allDocumentKeys.size() + 1, // +1 for patient
            needsPagination
        );
    }
    
    /**
     * Get next page of results using continuation token
     */
    public List<Resource> getPatientEverythingNextPage(String continuationToken, int offset, Integer count) {
        // Get current bucket from tenant context
        String bucketName = com.couchbase.fhir.resources.config.TenantContextHolder.getTenantId();
        
        // Retrieve pagination state
        com.couchbase.fhir.resources.search.PaginationState paginationState = 
            searchStateManager.getPaginationState(continuationToken, bucketName);
        
        if (paginationState == null) {
            logger.warn("❌ Pagination state not found or expired for token: {}", continuationToken);
            // Return 410 Gone for expired/invalid pagination token (FHIR standard)
            throw new ResourceGoneException("Pagination state has expired or is invalid. Please repeat your original $everything request.");
        }
        
        // Get document keys for this page using offset from URL (not from document)
        List<String> allDocumentKeys = paginationState.getAllDocumentKeys();
        int pageSize = (count != null && count > 0) ? count : paginationState.getPageSize();
        int fromIndex = Math.min(offset, allDocumentKeys.size());
        int toIndex = Math.min(offset + pageSize, allDocumentKeys.size());
        List<String> pageKeys = fromIndex < toIndex ? allDocumentKeys.subList(fromIndex, toIndex) : List.of();
        
        if (pageKeys.isEmpty()) {
            logger.info("🔑 No more results for pagination token: {}", continuationToken);
            return new ArrayList<>();
        }
        
        // Calculate current page for logging (1-based)
        int currentPage = (offset / pageSize) + 1;
        int totalPages = (int) Math.ceil((double) allDocumentKeys.size() / pageSize);
        
        logger.info("🔑 Fetching {} resources for page {}/{}", 
                   pageKeys.size(), currentPage, totalPages);
        
        // Fetch resources for this page
        return fetchResourcesByKeys(pageKeys, paginationState.getBucketName());
    }
    
    /**
     * Get the patient resource via KV lookup
     */
    private Patient getPatientResource(String patientId, String bucketName) {
        String patientKey = "Patient/" + patientId;
        String collectionName = collectionRoutingService.getTargetCollection("Patient");
        
        logger.debug("🌍 KV GET: bucket={}, collection={}, key={}", bucketName, collectionName, patientKey);
        
        try {
            // Use batch service to get single document
            List<Resource> resources = batchKvService.getDocuments(List.of(patientKey), "Patient");
            
            if (resources == null || resources.isEmpty()) {
                throw new ResourceNotFoundException("Patient/" + patientId + " not found");
            }
            
            Resource resource = resources.get(0);
            if (!(resource instanceof Patient)) {
                throw new ResourceNotFoundException("Resource at Patient/" + patientId + " is not a Patient");
            }
            
            logger.debug("✅ Found Patient/{}", patientId);
            return (Patient) resource;
            
        } catch (Exception e) {
            logger.error("❌ Failed to retrieve Patient/{}: {}", patientId, e.getMessage());
            throw new ResourceNotFoundException("Patient/" + patientId + " not found");
        }
    }
    
    /**
     * Determine which collections to search based on _type parameter
     * If no _type specified, search all collections (except Versions/Tombstones)
     */
    private List<String> determineCollections(String types) {
        // Get all collections with FTS indexes
        Map<String, String> allCollections = mappingConfig.getCollectionToFtsIndex();
        
        if (types == null || types.trim().isEmpty()) {
            // Search all collections except excluded ones
            return allCollections.keySet().stream()
                .filter(collection -> !EXCLUDED_COLLECTIONS.contains(collection))
                .collect(Collectors.toList());
        }
        
        // Parse comma-separated resource types and map to collections
        Set<String> requestedCollections = Arrays.stream(types.split(","))
            .map(String::trim)
            .map(mappingConfig::getCollectionForResource)
            .filter(Objects::nonNull)
            .filter(collection -> !EXCLUDED_COLLECTIONS.contains(collection))
            .collect(Collectors.toSet());
        
        if (requestedCollections.isEmpty()) {
            logger.warn("🌍 No valid collections for _type parameter: {}, using all collections", types);
            return allCollections.keySet().stream()
                .filter(collection -> !EXCLUDED_COLLECTIONS.contains(collection))
                .collect(Collectors.toList());
        }
        
        return new ArrayList<>(requestedCollections);
    }
    
    /**
     * Search for all related resource KEYS across all collections (FTS only, no KV fetch)
     * This is used for pagination - we get all keys first, then fetch pages as needed
     */
    private List<String> searchRelatedResourceKeys(
            String patientId,
            List<String> collections,
            Date start,
            Date end,
            Date since,
            String bucketName) {
        
        String patientReference = "Patient/" + patientId;
        List<String> allDocumentKeys = new ArrayList<>();
        
        // Loop through each collection and search via FTS (keys only)
        for (String collectionName : collections) {
            try {
                String ftsIndex = mappingConfig.getFtsIndexForCollection(collectionName);
                if (ftsIndex == null) {
                    logger.warn("🌍 No FTS index found for collection: {}", collectionName);
                    continue;
                }
                
                List<String> keys = searchCollectionForPatientKeys(
                    collectionName,
                    ftsIndex,
                    patientReference, 
                    start, 
                    end, 
                    since,
                    bucketName
                );
                
                logger.info("🌍 Found {} keys in {} collection for Patient/{}", 
                           keys.size(), collectionName, patientId);
                allDocumentKeys.addAll(keys);
                
            } catch (Exception e) {
                logger.warn("🌍 Failed to search {} collection for Patient/{}: {}", 
                           collectionName, patientId, e.getMessage());
            }
        }
        
        logger.info("🌍 Total keys found across {} collections: {}", collections.size(), allDocumentKeys.size());
        return allDocumentKeys;
    }
    
    /**
     * Fetch resources by their keys (batch KV operation)
     */
    private List<Resource> fetchResourcesByKeys(List<String> documentKeys, String bucketName) {
        // Group keys by resource type
        Map<String, List<String>> keysByResourceType = new HashMap<>();
        for (String key : documentKeys) {
            String resourceType = extractResourceTypeFromKey(key);
            keysByResourceType.computeIfAbsent(resourceType, k -> new ArrayList<>()).add(key);
        }
        
        // Retrieve documents grouped by resource type
        List<Resource> resources = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
            String resourceType = entry.getKey();
            List<String> keys = entry.getValue();
            
            try {
                List<Resource> batchResources = batchKvService.getDocuments(keys, resourceType);
                resources.addAll(batchResources);
                logger.debug("🌍 Retrieved {}/{} {} resources", 
                           batchResources.size(), keys.size(), resourceType);
            } catch (Exception e) {
                logger.warn("🌍 Failed to retrieve {} documents: {}", resourceType, e.getMessage());
            }
        }
        
        return resources;
    }
    
    /**
     * Search a specific collection for resource KEYS that reference the patient
     * Uses the SAME query for ALL collections: (patient.reference OR subject.reference) = "Patient/123"
     * All FTS indexes have both fields, so this works universally!
     * Returns only KEYS, not full resources (for pagination efficiency)
     */
    private List<String> searchCollectionForPatientKeys(
            String collectionName,
            String ftsIndexName,
            String patientReference,
            Date start,
            Date end,
            Date since,
            String bucketName) {
        
        // Build FTS query - SAME for ALL collections!
        List<SearchQuery> queries = new ArrayList<>();
        
        // Always search BOTH reference fields (all FTS indexes have both indexed)
        SearchQuery patientRefQuery = SearchQuery.match(patientReference).field("patient.reference");
        SearchQuery subjectRefQuery = SearchQuery.match(patientReference).field("subject.reference");
        queries.add(SearchQuery.disjuncts(patientRefQuery, subjectRefQuery));
        
        // Add date range filter if specified (for effectiveDateTime, issued, recorded, etc.)
        if (start != null || end != null) {
            queries.add(buildDateRangeQuery(start, end));
        }
        
        // Add _since filter if specified (meta.lastUpdated)
        if (since != null) {
            String sinceStr = formatDateForFts(since);
            queries.add(SearchQuery.dateRange()
                .field("meta.lastUpdated")
                .start(sinceStr, true));
        }
        
        // Sort by meta.lastUpdated DESC (newest first) - consistent across all collections
        List<SearchSort> sortFields = List.of(
            SearchSort.byField("meta.lastUpdated").desc(true)
        );
        
        logger.debug("🌍 FTS search on {} (sorted by lastUpdated DESC): {} queries", ftsIndexName, queries.size());
        
        // Execute FTS search directly on the collection's index
        // Use searchForAllKeysInCollection to bypass resource-type routing
        FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForAllKeysInCollection(
            queries,
            ftsIndexName,
            sortFields,
            bucketName
        );
        
        return ftsResult.getDocumentKeys();
    }
    
    /**
     * Extract resource type from document key (e.g., "Observation/123" -> "Observation")
     */
    private String extractResourceTypeFromKey(String key) {
        int slashIndex = key.indexOf('/');
        return slashIndex > 0 ? key.substring(0, slashIndex) : key;
    }
    
    /**
     * Build date range query for clinical date fields
     * Note: Different resources use different date fields (effectiveDateTime, issued, etc.)
     */
    private SearchQuery buildDateRangeQuery(Date start, Date end) {
        // Common date fields across clinical resources
        List<String> dateFields = List.of(
            "effectiveDateTime",  // Observation
            "issued",             // DiagnosticReport
            "recordedDate",       // Condition
            "performedDateTime",  // Procedure
            "occurrenceDateTime", // Immunization, MedicationRequest
            "authoredOn"          // ServiceRequest, MedicationRequest
        );
        
        List<SearchQuery> dateQueries = new ArrayList<>();
        
        for (String field : dateFields) {
            if (start != null && end != null) {
                dateQueries.add(SearchQuery.dateRange()
                    .field(field)
                    .start(formatDateForFts(start), true)
                    .end(formatDateForFts(end), true));
            } else if (start != null) {
                dateQueries.add(SearchQuery.dateRange()
                    .field(field)
                    .start(formatDateForFts(start), true));
            } else if (end != null) {
                dateQueries.add(SearchQuery.dateRange()
                    .field(field)
                    .end(formatDateForFts(end), true));
            }
        }
        
        // Return disjunction (any of the date fields match)
        return SearchQuery.disjuncts(dateQueries.toArray(new SearchQuery[0]));
    }
    
    /**
     * Format date for FTS query (ISO 8601 format)
     */
    private String formatDateForFts(Date date) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(date);
    }
}

