package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.sampledata.service.SampleDataService;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.FhirBundleProcessingService;
import com.couchbase.fhir.resources.util.BulkJob;
import com.couchbase.fhir.resources.util.BulkTask;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseDatatype;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.UriType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.couchbase.client.core.io.CollectionIdentifier.DEFAULT_SCOPE;

@Component
public class BulkImportProvider {

    private static final Logger logger = LoggerFactory.getLogger(BulkImportProvider.class);

    @Autowired
    private FhirBucketConfigService bucketConfigService;


    private final ConnectionService connectionService;

    private final FhirContext fhirContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final FhirBundleProcessingService fhirBundleProcessingService;

    public BulkImportProvider(FhirContext fhirContext , ConnectionService connectionService , FhirBundleProcessingService fhirBundleProcessingService) {
        this.fhirContext = fhirContext;
        this.connectionService = connectionService;
        this.fhirBundleProcessingService = fhirBundleProcessingService;
    }


    @Operation(name = "$import", idempotent = false, manualResponse = true)
    public void importBulkData(@ResourceParam Parameters parameters , RequestDetails requestDetails , HttpServletRequest request, HttpServletResponse response) throws IOException {

        String bucketName = TenantContextHolder.getTenantId();
        logger.info("📦 Starting bulk import for : {}", bucketName);


        BulkJob bulkJob = new BulkJob();
        bulkJob.setJobId(UUID.randomUUID().toString());
        bulkJob.setStatus("PENDING");
        List<BulkTask> taskList = new ArrayList<>();

        parameters.getParameter().stream()
                .filter(p -> "input".equals(p.getName()))
                .forEach(input -> {
                    BulkTask bulkTask = new BulkTask();
                    String url = getPartValue(input, "url");
                    bulkTask.setTaskId(UUID.randomUUID().toString());
                    bulkTask.setFileUrl(url);
                    taskList.add(bulkTask);
                });

        bulkJob.setTaskList(taskList);
        String documentKey = "BulkJob" + "/" + bulkJob.getJobId();

        com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");

        com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                .scope("Resources")
                .collection("BulkJob");

        collection.insert(documentKey, bulkJob);

        Parameters result = new Parameters();
        result.addParameter().setName("jobId").setValue(new StringType(bulkJob.getJobId()));

        executor.submit(() -> processJob(bulkJob , bucketName));

        // 📨 Send HTTP headers
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setHeader("Content-Location", "JOB URL");
        response.setContentType("application/fhir+json");

        // 🧾 Write JSON body
        requestDetails.getFhirContext()
                .newJsonParser()
                .setPrettyPrint(true)
                .encodeResourceToWriter(result, response.getWriter());

        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setHeader("Content-Location", "JOB URL");

    }

    public void processJob(BulkJob bulkJob , String bucketName){

        try{
            com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");

            com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                    .scope("Resources")
                    .collection("BulkJob");

            String documentKey = "BulkJob" + "/" + bulkJob.getJobId();
            bulkJob.setStatus("INPROGRESS");
            collection.upsert(documentKey, bulkJob);

            for (BulkTask task : bulkJob.getTaskList()) {

                String fileUrl= task.getFileUrl();

                int processedFiles = 0;
                URL url = new URL(fileUrl);
                try (InputStream zipStream = url.openStream();
                     ZipInputStream zis = new ZipInputStream(zipStream)) {

                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (shouldProcessEntry(entry)) {
                            String fileName = entry.getName();
                            logger.info("Processing file {}", fileName);
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = zis.read(buffer)) != -1) {
                                baos.write(buffer, 0, bytesRead);
                            }
                            byte[] jsonBytes = baos.toByteArray();
                            String jsonContent = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);

                            logger.debug("🔍 Processing JSON content (length: {} chars)", jsonContent.length());
                            com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bulkImportConfig =
                                    new com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig();
                            bulkImportConfig.setValidationMode("disabled");
                            fhirBundleProcessingService.processBundleTransaction(jsonContent , "default" , bucketName , bulkImportConfig);

                            processedFiles++;

                        }
                    }
                    bulkJob.setStatus("COMPLETED");
                    collection.upsert(documentKey, bulkJob);
                    logger.info("Total {} files processed successfully ", processedFiles);
                }

            }


        }catch(Exception e){
            logger.error("Error in bulk job process "+e.getMessage());
        }
    }

    /**
     * Check if a ZIP entry should be processed (filters out macOS metadata files)
     */
    private boolean shouldProcessEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }

        String name = entry.getName();

        // Must be a JSON file
        if (!name.endsWith(".json")) {
            return false;
        }

        // Filter out macOS metadata files
        if (name.contains("__MACOSX") ||
                name.contains(".DS_Store") ||
                name.startsWith("._") ||
                name.contains("/._")) { // Also check for nested ._* files
            return false;
        }

        return true;
    }

    @Operation(name = "$bulkstatus", idempotent = true, manualResponse = true)
    public void getBulkJobStatus(
            @OperationParam(name = "jobId") String jobId,
            HttpServletResponse response
    ) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        String documentKey = "BulkJob" + "/" + jobId;

        com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");

        com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                .scope("Resources")
                .collection("BulkJob");

        com.couchbase.client.java.kv.GetResult result = collection.get(documentKey);

        // Set HTTP response
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(result.contentAsObject().toString());
    }


    private String getParameterValue(Parameters parameters, String name) {
        return parameters.getParameter()
                .stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .map(p -> {
                    IBaseDatatype value = p.getValue();
                    if (value instanceof StringType) return ((StringType) value).getValue();
                    if (value instanceof UriType) return ((UriType) value).getValue();
                    return null;
                })
                .orElse(null);
    }


    private String getPartValue(Parameters.ParametersParameterComponent param, String partName) {
        return param.getPart()
                .stream()
                .filter(p -> partName.equals(p.getName()))
                .findFirst()
                .map(p -> {
                    IBaseDatatype value = p.getValue();
                    if (value instanceof StringType) return ((StringType) value).getValue();
                    if (value instanceof UriType) return ((UriType) value).getValue();
                    return null;
                })
                .orElse(null);
    }

}
