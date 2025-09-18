package com.couchbase.admin.fhirResource.controller;

import com.couchbase.admin.fhirResource.model.DocumentKeyRequest;
import com.couchbase.admin.fhirResource.model.DocumentKeyResponse;
import com.couchbase.admin.fhirResource.model.DocumentMetadata;
import com.couchbase.admin.fhirResource.model.DocumentMetadataResponse;
import com.couchbase.admin.fhirResource.service.FhirDocumentAdminService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fhir-resources")
@CrossOrigin(origins = "*")
public class FhirDocumentAdminController {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirDocumentAdminController.class);
    
    @Autowired
    private FhirDocumentAdminService fhirDocumentAdminService;
    
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
            logger.debug("Getting document keys for connection: {}, bucket: {}, collection: {}, page: {}, pageSize: {}, patientId: {}", 
                       connectionName, bucketName, collectionName, page, pageSize, patientId);
            
            DocumentKeyRequest request = new DocumentKeyRequest();
            request.setBucketName(bucketName);
            request.setCollectionName(collectionName);
            request.setPage(page);
            request.setPageSize(pageSize);
            request.setPatientId(patientId);
            
            DocumentKeyResponse response = fhirDocumentAdminService.getDocumentKeys(request, connectionName);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to get document keys", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get document metadata using FTS search for a specific FHIR collection with pagination
     * GET /api/fhir-resources/document-metadata?connectionName=myConn&bucketName=synthea&collectionName=Patient&page=0&pageSize=10&patientId=optional
     */
    @GetMapping("/document-metadata")
    public ResponseEntity<DocumentMetadataResponse> getDocumentMetadata(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String collectionName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String patientId) {
        
        try {
            logger.debug("Getting document metadata for connection: {}, bucket: {}, collection: {}, page: {}, pageSize: {}, patientId: {}", 
                       connectionName, bucketName, collectionName, page, pageSize, patientId);
            
            DocumentKeyRequest request = new DocumentKeyRequest();
            request.setBucketName(bucketName);
            request.setCollectionName(collectionName);
            request.setPage(page);
            request.setPageSize(pageSize);
            request.setPatientId(patientId);
            
            DocumentMetadataResponse response = fhirDocumentAdminService.getDocumentMetadata(request, connectionName);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to get document metadata", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get version history for a specific document ID
     * GET /api/fhir-resources/version-history?connectionName=myConn&bucketName=synthea&documentId=12345
     */
    @GetMapping("/version-history")
    public ResponseEntity<List<DocumentMetadata>> getVersionHistory(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String documentId) {
        
        try {
            logger.debug("Getting version history for connection: {}, bucket: {}, documentId: {}", 
                       connectionName, bucketName, documentId);
            
            List<DocumentMetadata> versions = fhirDocumentAdminService.getVersionHistory(bucketName, documentId, connectionName);
            
            return ResponseEntity.ok(versions);
            
        } catch (Exception e) {
            logger.error("Failed to get version history", e);
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
            logger.debug("Getting document for connection: {}, bucket: {}, collection: {}, key: {}", 
                       connectionName, bucketName, collectionName, documentKey);
            
            Object document = fhirDocumentAdminService.getDocument(bucketName, collectionName, documentKey, connectionName);
            
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
