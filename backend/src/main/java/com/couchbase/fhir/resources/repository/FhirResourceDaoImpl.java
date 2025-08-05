package com.couchbase.fhir.resources.repository;
import ca.uhn.fhir.context.FhirContext;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.couchbase.client.java.query.QueryOptions.queryOptions;


public class FhirResourceDaoImpl<T extends IBaseResource> implements  FhirResourceDao<T> {

    private static final Logger logger = LoggerFactory.getLogger(FhirResourceDaoImpl.class);
    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";


    private final ConnectionService connectionService;
    private final FhirContext fhirContext;

    private final Class<T> resourceClass;

    public FhirResourceDaoImpl(Class<T> resourceClass , ConnectionService connectionService , FhirContext fhirContext) {
        this.resourceClass = resourceClass;
        this.connectionService = connectionService;
        this.fhirContext = fhirContext;
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
            // Build N1QL query to get specific document using ResourceType::id format
            String documentKey = resourceType + "/" + id;
            String query = String.format(
                    "SELECT c.* " +
                            "FROM `%s`.`%s`.`%s` c " +
                            "USE KEYS '%s'",
                    bucketName, DEFAULT_SCOPE, resourceType, documentKey
            );

            QueryResult result = cluster.query(query, queryOptions()
                    .parameters(JsonObject.create().put("key", documentKey)));

            if (result.rowsAsObject().isEmpty()) return Optional.empty();

            JsonObject json = result.rowsAsObject().get(0);
            @SuppressWarnings("unchecked")
            T resource = (T) fhirContext.newJsonParser().parseResource(json.toString());
            return Optional.of(resource);

        } catch (Exception e) {
            logger.error("Failed to get {}/{}: {}", resourceType, id, e.getMessage());
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

            String documentKey = resourceType + "/" + resource.getIdElement().getIdPart();
            System.out.println("document key --- ");
            System.out.println(documentKey);
            String resourceJson = fhirContext.newJsonParser().encodeResourceToString(resource);

            String insertQuery = String.format(
                    "INSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ($key, $value)",
                    bucketName, DEFAULT_SCOPE, resourceType
            );

            JsonObject params = JsonObject.create()
                    .put("key", documentKey)
                    .put("value", JsonObject.fromJson(resourceJson));

            QueryResult result = cluster.query(insertQuery, queryOptions().parameters(params));
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

            String connectionName = getDefaultConnection();

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }


          //  String query = String.format("SELECT c.* FROM `%s`.`%s`.`%s` c %s LIMIT 50",
           //         DEFAULT_BUCKET, DEFAULT_SCOPE, resourceType, whereClause);

            QueryResult result = cluster.query(query);
            List<JsonObject> rows = result.rowsAs(JsonObject.class);

            for (JsonObject row : rows) {
                Map.Entry<String, Object> entry = row.toMap().entrySet().iterator().next();
                Object value = entry.getValue();
                JsonObject nestedResource = JsonObject.from((Map<String, Object>) value);
                T resource = (T) fhirContext.newJsonParser().parseResource(nestedResource.toString());
                resources.add(resource);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resources;
    }

    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }
}
