package com.couchbase.admin.fhirResource.controller;

import com.couchbase.admin.fhirResource.model.DocumentKeyRequest;
import com.couchbase.admin.fhirResource.model.DocumentKeyResponse;
import com.couchbase.admin.fhirResource.service.FhirResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fhir-resources")
@CrossOrigin(origins = "*")
public class FhirResourceController {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirResourceController.class);
    
    @Autowired
    private FhirResourceService fhirResourceService;
    
    /**
     * Get document keys for a specific FHIR collection with pagination
     * GET /api/fhir-resources/document-keys?connectionName=myConn&bucketName=synthea&collectionName=Patient&page=0&pageSize=10&patientId=optional
     */
    @GetMapping("/document-keys")
    public ResponseEntity<DocumentKeyResponse> getDocumentKeys(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String collectionName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String patientId) {
        
        try {
            logger.info("Getting document keys for connection: {}, bucket: {}, collection: {}, page: {}, pageSize: {}, patientId: {}", 
                       connectionName, bucketName, collectionName, page, pageSize, patientId);
            
            DocumentKeyRequest request = new DocumentKeyRequest();
            request.setBucketName(bucketName);
            request.setCollectionName(collectionName);
            request.setPage(page);
            request.setPageSize(pageSize);
            request.setPatientId(patientId);
            
            DocumentKeyResponse response = fhirResourceService.getDocumentKeys(request, connectionName);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to get document keys", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get a specific FHIR document by its key
     * GET /api/fhir-resources/document?connectionName=myConn&bucketName=synthea&collectionName=Patient&documentKey=Patient::123
     */
    @GetMapping("/document")
    public ResponseEntity<Object> getDocument(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String collectionName,
            @RequestParam String documentKey) {
        
        try {
            logger.info("Getting document for connection: {}, bucket: {}, collection: {}, key: {}", 
                       connectionName, bucketName, collectionName, documentKey);
            
            Object document = fhirResourceService.getDocument(bucketName, collectionName, documentKey, connectionName);
            
            if (document != null) {
                return ResponseEntity.ok(document);
            } else {
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            logger.error("Failed to get document", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
