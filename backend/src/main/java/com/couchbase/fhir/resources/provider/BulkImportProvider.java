package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
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

@Component
public class BulkImportProvider {

    private static final Logger logger = LoggerFactory.getLogger(BulkImportProvider.class);

    @Autowired
    private FhirBucketConfigService bucketConfigService;

    private final FhirContext fhirContext;

    public BulkImportProvider(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }


    @Operation(name = "$import", idempotent = false, manualResponse = true)
    public void importBulkData(@ResourceParam Parameters parameters , RequestDetails requestDetails , HttpServletRequest request, HttpServletResponse response) throws IOException {

        String tenantId = TenantContextHolder.getTenantId();
        logger.info("📦 Starting bulk import for tenant: {}", tenantId);

        String inputFormat = getParameterValue(parameters, "inputFormat");
        String inputSource = getParameterValue(parameters, "inputSource");

        logger.info("⚙️ InputFormat: {}", inputFormat);
        logger.info("📁 InputSource: {}", inputSource);


        parameters.getParameter().stream()
                .filter(p -> "input".equals(p.getName()))
                .forEach(input -> {
                    String resourceType = getPartValue(input, "type");
                    String url = getPartValue(input, "url");
                    logger.info("📥 Resource Type: {}, URL: {}", resourceType, url);
                });


        Parameters result = new Parameters();
        result.addParameter().setName("jobId").setValue(new StringType("123"));

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
