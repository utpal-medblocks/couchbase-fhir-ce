package com.couchbase.fhir.auth.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.users.model.User;
import com.couchbase.admin.users.service.UserService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service to resolve patient context for SMART on FHIR OAuth flows
 * 
 * Resolves patient ID in this priority order:
 * 1. Explicit patient_id from authorization request (stored in OAuth2Authorization)
 * 2. User's default patient ID from fhir.Admin.users
 * 3. First patient resource the user owns/has access to (fallback)
 * 
 * The resolved patient ID is included in the JWT access token's "patient" claim.
 */
@Service
public class PatientContextService {
    
    private static final Logger logger = LoggerFactory.getLogger(PatientContextService.class);
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired(required = false)
    private OAuth2AuthorizationService authorizationService;
    
    /**
     * Resolve patient context for a given user
     * 
     * @param userId User email/ID
     * @param authorizationId OAuth2 authorization ID (optional, for checking explicit patient_id)
     * @return Patient ID (just the ID, not "Patient/id"), or null if none found
     */
    public String resolvePatientContext(String userId, String authorizationId) {
        logger.debug("🔍 [PATIENT-CONTEXT] Resolving patient for user: {}", userId);
        
        // Priority 1: Explicit patient_id from authorization request
        if (authorizationId != null && authorizationService != null) {
            String explicitPatientId = getExplicitPatientId(authorizationId);
            if (explicitPatientId != null) {
                logger.info("✅ [PATIENT-CONTEXT] Using explicit patient_id: {}", explicitPatientId);
                return explicitPatientId;
            }
        }
        
        // Priority 2: User's default patient
        Optional<User> userOpt = userService.getUserById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (user.getDefaultPatientId() != null && !user.getDefaultPatientId().isEmpty()) {
                logger.info("✅ [PATIENT-CONTEXT] Using user's default patient: {}", user.getDefaultPatientId());
                return user.getDefaultPatientId();
            }
        }
        
        // Priority 3: Find first patient the user has access to
        String firstPatientId = findFirstAccessiblePatient(userId);
        if (firstPatientId != null) {
            logger.info("✅ [PATIENT-CONTEXT] Using first accessible patient: {}", firstPatientId);
            return firstPatientId;
        }
        
        logger.warn("⚠️ [PATIENT-CONTEXT] No patient context found for user: {}", userId);
        return null;
    }
    
    /**
     * Get explicit patient_id from OAuth2 authorization attributes
     * This would be set if authorization request included patient_id parameter
     */
    private String getExplicitPatientId(String authorizationId) {
        try {
            if (authorizationService == null) {
                return null;
            }
            
            OAuth2Authorization authorization = authorizationService.findById(authorizationId);
            if (authorization == null) {
                return null;
            }
            
            // Check if patient_id was passed as additional parameter
            Object patientIdAttr = authorization.getAttribute("patient_id");
            if (patientIdAttr != null) {
                return patientIdAttr.toString();
            }
            
            return null;
        } catch (Exception e) {
            logger.debug("Could not retrieve explicit patient_id: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Find the first patient resource that the user has access to
     * This is a fallback when no explicit or default patient is set
     * 
     * Strategy: Query for Patient resources, return first one found
     */
    private String findFirstAccessiblePatient(String userId) {
        try {
            Cluster cluster = connectionService.getConnection("default");
            if (cluster == null) {
                logger.debug("No Couchbase connection available");
                return null;
            }
            
            // Query for first Patient resource
            // Note: In production, this should filter by user's access rights
            String query = "SELECT META().id FROM `fhir`.`default`.`Patient` LIMIT 1";
            
            var result = cluster.query(query);
            var rows = result.rowsAsObject();
            
            if (!rows.isEmpty()) {
                JsonObject row = rows.get(0);
                String docId = row.getString("id");
                
                // Extract patient ID from document key (format: "Patient::patient-id")
                if (docId != null && docId.startsWith("Patient::")) {
                    String patientId = docId.substring(9); // Remove "Patient::" prefix
                    logger.debug("Found accessible patient: {}", patientId);
                    return patientId;
                }
            }
            
            return null;
        } catch (Exception e) {
            logger.debug("Could not find accessible patient: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Validate that a patient ID exists and user has access
     * 
     * @param patientId Patient ID to validate
     * @param userId User requesting access
     * @return true if valid and accessible
     */
    public boolean validatePatientAccess(String patientId, String userId) {
        if (patientId == null || patientId.isEmpty()) {
            return false;
        }
        
        try {
            Cluster cluster = connectionService.getConnection("default");
            if (cluster == null) {
                return false;
            }
            
            // Check if patient exists
            String docKey = "Patient::" + patientId;
            String query = String.format(
                "SELECT META().id FROM `fhir`.`default`.`Patient` USE KEYS '%s'", 
                docKey
            );
            
            var result = cluster.query(query);
            boolean exists = !result.rowsAsObject().isEmpty();
            
            if (exists) {
                logger.debug("✅ Patient {} exists and is accessible", patientId);
            } else {
                logger.warn("⚠️ Patient {} not found or not accessible", patientId);
            }
            
            return exists;
            
            // TODO: In production, add user access rights check here
            // Check if userId has permission to access this patient
            
        } catch (Exception e) {
            logger.error("Error validating patient access: {}", e.getMessage());
            return false;
        }
    }
}

