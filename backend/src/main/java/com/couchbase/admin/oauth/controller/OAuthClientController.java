package com.couchbase.admin.oauth.controller;

import com.couchbase.admin.oauth.dto.OAuthClientResponse;
import com.couchbase.admin.oauth.model.OAuthClient;
import com.couchbase.admin.oauth.service.OAuthClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for OAuth Client management
 * Protected by Admin UI JWT authentication (/api/admin/*)
 */
@RestController
@RequestMapping("/api/admin/oauth-clients")
public class OAuthClientController {
    
    private static final Logger logger = LoggerFactory.getLogger(OAuthClientController.class);
    
    @Autowired
    private OAuthClientService clientService;
    
    /**
     * Get all OAuth clients
     * GET /api/admin/oauth-clients
     */
    @GetMapping
    public ResponseEntity<List<OAuthClientResponse>> getAllClients() {
        logger.info("📋 Fetching all OAuth clients");
        List<OAuthClient> clients = clientService.getAllClients();
        List<OAuthClientResponse> dtos = clients.stream()
                .map(OAuthClientResponse::from)
                .toList();
        return ResponseEntity.ok(dtos);
    }
    
    /**
     * Get OAuth client by ID
     * GET /api/admin/oauth-clients/{clientId}
     */
    @GetMapping("/{clientId}")
    public ResponseEntity<?> getClientById(@PathVariable String clientId) {
        logger.info("🔍 Fetching OAuth client: {}", clientId);
        return clientService.getClientById(clientId)
                .map(client -> ResponseEntity.ok(OAuthClientResponse.from(client)))
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Create new OAuth client
     * POST /api/admin/oauth-clients
     */
    @PostMapping
    public ResponseEntity<?> createClient(
            @RequestBody OAuthClient client,
            Authentication authentication) {
        
        try {
            // Get current user ID from authentication
            String createdBy = authentication != null ? authentication.getName() : "system";
            
            logger.info("➕ Creating OAuth client: {} by {}", client.getClientName(), createdBy);
            
            // Generate plain client secret for confidential clients
            String plainSecret = null;
            if ("confidential".equals(client.getAuthenticationType())) {
                plainSecret = "secret-" + UUID.randomUUID().toString();
            }
            
            OAuthClient createdClient = clientService.createClient(client, plainSecret, createdBy);
            
            // Return response with plain secret (shown only once)
            if (plainSecret != null) {
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(OAuthClientResponse.fromWithSecret(createdClient, plainSecret));
            } else {
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(OAuthClientResponse.from(createdClient));
            }
            
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error creating OAuth client: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error creating OAuth client", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create OAuth client"));
        }
    }
    
    /**
     * Update OAuth client
     * PUT /api/admin/oauth-clients/{clientId}
     */
    @PutMapping("/{clientId}")
    public ResponseEntity<?> updateClient(
            @PathVariable String clientId,
            @RequestBody OAuthClient updates) {
        
        try {
            logger.info("🔄 Updating OAuth client: {}", clientId);
            OAuthClient updatedClient = clientService.updateClient(clientId, updates);
            return ResponseEntity.ok(OAuthClientResponse.from(updatedClient));
            
        } catch (Exception e) {
            logger.error("❌ Error updating OAuth client: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update OAuth client"));
        }
    }
    
    /**
     * Revoke OAuth client
     * POST /api/admin/oauth-clients/{clientId}/revoke
     */
    @PostMapping("/{clientId}/revoke")
    public ResponseEntity<?> revokeClient(@PathVariable String clientId) {
        try {
            logger.info("🚫 Revoking OAuth client: {}", clientId);
            clientService.revokeClient(clientId);
            return ResponseEntity.ok(Map.of("message", "Client revoked successfully"));
            
        } catch (Exception e) {
            logger.error("❌ Error revoking OAuth client: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to revoke OAuth client"));
        }
    }
    
    /**
     * Delete OAuth client
     * DELETE /api/admin/oauth-clients/{clientId}
     */
    @DeleteMapping("/{clientId}")
    public ResponseEntity<?> deleteClient(@PathVariable String clientId) {
        try {
            logger.info("🗑️  Deleting OAuth client: {}", clientId);
            clientService.deleteClient(clientId);
            return ResponseEntity.ok(Map.of("message", "Client deleted successfully"));
            
        } catch (Exception e) {
            logger.error("❌ Error deleting OAuth client: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete OAuth client"));
        }
    }

    /**
     * Attach a bulk group id to a client
     * POST /api/admin/oauth-clients/{clientId}/bulk-group
     */
    @PostMapping("/{clientId}/bulk-group")
    public ResponseEntity<?> attachBulkGroup(@PathVariable String clientId, @RequestBody Map<String, String> body) {
        try {
            String bulkGroupId = body.get("bulkGroupId");
            if (bulkGroupId == null || bulkGroupId.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "bulkGroupId is required"));
            }
            OAuthClient updated = clientService.attachBulkGroup(clientId, bulkGroupId);
            return ResponseEntity.ok(OAuthClientResponse.from(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Error attaching bulk group to OAuth client: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to attach bulk group"));
        }
    }

    /**
     * Get bulk group id attached to client
     * GET /api/admin/oauth-clients/{clientId}/bulk-group
     */
    @GetMapping("/{clientId}/bulk-group")
    public ResponseEntity<?> getBulkGroup(@PathVariable String clientId) {
        try {
            return clientService.getClientById(clientId)
                    .map(c -> ResponseEntity.ok(OAuthClientResponse.from(c)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("❌ Error fetching bulk group for OAuth client: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to fetch bulk group"));
        }
    }

    /**
     * Detach bulk group from client
     * DELETE /api/admin/oauth-clients/{clientId}/bulk-group
     */
    @DeleteMapping("/{clientId}/bulk-group")
    public ResponseEntity<?> detachBulkGroup(@PathVariable String clientId) {
        try {
            OAuthClient updated = clientService.detachBulkGroup(clientId);
            return ResponseEntity.ok(OAuthClientResponse.from(updated));
        } catch (Exception e) {
            logger.error("❌ Error detaching bulk group for OAuth client: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to detach bulk group"));
        }
    }
}

