package com.couchbase.fhir.resources.repository;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.resources.service.CollectionRoutingService;
import com.couchbase.fhir.resources.interceptor.DAOTimingContext;
import com.google.common.base.Stopwatch;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


public class FhirResourceDaoImpl<T extends IBaseResource> implements  FhirResourceDao<T> {

    private static final Logger logger = LoggerFactory.getLogger(FhirResourceDaoImpl.class);
    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";


    private final ConnectionService connectionService;
    private final FhirContext fhirContext;
    private final CollectionRoutingService collectionRoutingService;

    public FhirResourceDaoImpl(Class<T> resourceClass , ConnectionService connectionService , FhirContext fhirContext, CollectionRoutingService collectionRoutingService) {
        this.connectionService = connectionService;
        this.fhirContext = fhirContext;
        this.collectionRoutingService = collectionRoutingService;
    }


    @Override
    public Optional<T> read(String resourceType, String id , String bucketName) {

        try{
            String connectionName =  getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }
            // Use direct KV get operation instead of N1QL
            String documentKey = resourceType + "/" + id;
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            logger.debug("KV GET: bucket={}, collection={}, key={}", bucketName, targetCollection, documentKey);
            
            // Get the collection and perform direct KV get
            com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                    .scope(DEFAULT_SCOPE)
                    .collection(targetCollection);
            
            com.couchbase.client.java.kv.GetResult result = collection.get(documentKey);
            JsonObject json = result.contentAsObject();
            
            @SuppressWarnings("unchecked")
            T resource = (T) fhirContext.newJsonParser().parseResource(json.toString());
            return Optional.of(resource);

        } catch (Exception e) {
            logger.error("Failed to get {}/{}: {}", resourceType, id, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<T> readMultiple(String resourceType, List<String> ids, String bucketName) {
        List<T> resources = new ArrayList<>();
        
        if (ids == null || ids.isEmpty()) {
            return resources;
        }
        
        try {
            String connectionName = getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }
            
            // Use direct KV bulk get operations instead of N1QL
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                    .scope(DEFAULT_SCOPE)
                    .collection(targetCollection);
            
            logger.debug("KV BULK GET: bucket={}, collection={}, {} documents", bucketName, targetCollection, ids.size());
            
            // Perform individual KV gets (Couchbase doesn't have bulk get in sync API)
            for (String id : ids) {
                try {
                    String documentKey = resourceType + "/" + id;
                    com.couchbase.client.java.kv.GetResult result = collection.get(documentKey);
                    JsonObject json = result.contentAsObject();
                    
                    @SuppressWarnings("unchecked")
                    T resource = (T) fhirContext.newJsonParser().parseResource(json.toString());
                    resources.add(resource);
                } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
                    logger.debug("Document not found: {}/{}", resourceType, id);
                    // Skip missing documents
                } catch (Exception e) {
                    logger.warn("Failed to retrieve/parse {} resource {}: {}", resourceType, id, e.getMessage());
                }
            }
            
            logger.info("Successfully parsed {}/{} {} resources", resources.size(), ids.size(), resourceType);
            return resources;

        } catch (Exception e) {
            logger.error("Failed to get multiple {}: {}", resourceType, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<T> create(String resourceType , T resource , String bucketName) {

        try{
            String connectionName =  getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Use direct KV insert operation instead of N1QL
            String documentKey = resourceType + "/" + resource.getIdElement().getIdPart();
            String resourceJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            logger.debug("KV INSERT: bucket={}, collection={}, key={}", bucketName, targetCollection, documentKey);
            
            // Get the collection and perform direct KV insert
            com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                    .scope(DEFAULT_SCOPE)
                    .collection(targetCollection);
            
            collection.insert(documentKey, JsonObject.fromJson(resourceJson));
            logger.info("Successfully created {} with ID: {}", resourceType, documentKey);
            return Optional.of(resource);

        }catch (Exception e){
            logger.error("Failed to create {}: {}", resourceType, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public List<T> search(String resourceType , String query) {
        List<T> resources = new ArrayList<>();
        try {
            Stopwatch stopwatch = Stopwatch.createStarted();
            String connectionName = getDefaultConnection();

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            QueryResult result = cluster.query(query);

            long queryTimeMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            logger.info("Query execution time : " + queryTimeMs + " ms");
            DAOTimingContext.recordQueryTime(queryTimeMs);

            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            rows.stream()  // Use sequential stream to preserve FTS sort order
                    .map(row -> {
                        @SuppressWarnings("unchecked")
                        T resource = (T) parser.parseResource(row.toString());
                        return resource;
                    })
                    .forEach(resources::add);
            
            long totalTimeMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            long parsingTimeMs = totalTimeMs - queryTimeMs;
            logger.debug("Execution time after HAPI parsing result: " + totalTimeMs + " ms");
            DAOTimingContext.recordParsingTime(parsingTimeMs);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resources;
    }
    
    /**
     * Execute count query for _total=accurate operations
     */
    public int getCount(String resourceType, String countQuery) {
        try {
            Stopwatch stopwatch = Stopwatch.createStarted();
            String connectionName = getDefaultConnection();

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            QueryResult result = cluster.query(countQuery);
            logger.info("Count query execution time: " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");

            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            if (rows.isEmpty()) {
                return 0;
            }
            
            // Extract count from first row
            JsonObject firstRow = rows.get(0);
            Object totalObj = firstRow.get("total");
            
            if (totalObj instanceof Number) {
                return ((Number) totalObj).intValue();
            } else if (totalObj instanceof String) {
                return Integer.parseInt((String) totalObj);
            }
            
            return 0;

        } catch (Exception e) {
            logger.error("Failed to execute count query: {}", e.getMessage());
            return 0;
        }
    }

    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }
}
