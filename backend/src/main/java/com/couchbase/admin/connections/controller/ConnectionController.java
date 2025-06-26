package com.couchbase.admin.connections.controller;

import com.couchbase.admin.connections.model.Connection;
import com.couchbase.admin.connections.model.ConnectionRequest;
import com.couchbase.admin.connections.model.ConnectionResponse;
import com.couchbase.admin.connections.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/connections")
@CrossOrigin(origins = {"http://localhost:5173"}) // Allow React dev server (Vite)
public class ConnectionController {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionController.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    /**
     * Create and store a Couchbase connection
     */
    @PostMapping("/create")
    public ResponseEntity<ConnectionResponse> createConnection(@RequestBody ConnectionRequest request) {
        logger.info("Creating connection request for: {}", request.getName());
        
        try {
            ConnectionResponse response = connectionService.createConnection(request);
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            logger.error("Error creating connection: {}", e.getMessage(), e);
            ConnectionResponse errorResponse = ConnectionResponse.failure(
                "Internal server error", 
                e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Get list of active connections
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveConnections() {
        try {
            List<String> activeConnections = connectionService.getActiveConnections();
            
            Map<String, Object> response = Map.of(
                "success", true,
                "connections", activeConnections,
                "count", activeConnections.size()
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting active connections: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = Map.of(
                "success", false,
                "error", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Get all connections (for compatibility)
     */
    @GetMapping
    public ResponseEntity<List<Connection>> getAllConnections() {
        List<Connection> connections = connectionService.getAllConnections();
        return ResponseEntity.ok(connections);
    }

    /**
     * Get connection by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Connection> getConnectionById(@PathVariable String id) {
        Connection connection = connectionService.getConnectionById(id);
        if (connection != null) {
            return ResponseEntity.ok(connection);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Create connection (legacy endpoint)
     */
    @PostMapping
    public ResponseEntity<Connection> createConnectionLegacy(@RequestBody Connection connection) {
        Connection createdConnection = connectionService.createConnection(connection);
        return ResponseEntity.ok(createdConnection);
    }

    /**
     * Test connection
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Connection connection) {
        boolean isConnected = connectionService.testConnection(connection);
        Map<String, Object> response = new HashMap<>();
        response.put("success", isConnected);
        response.put("message", isConnected ? "Connection successful" : "Connection failed");
        return ResponseEntity.ok(response);
    }
    
    /**
     * Close a specific connection
     */
    @DeleteMapping("/{connectionName}")
    public ResponseEntity<Map<String, Object>> closeConnection(@PathVariable String connectionName) {
        logger.info("Closing connection: {}", connectionName);
        
        try {
            boolean closed = connectionService.closeConnection(connectionName);
            
            Map<String, Object> response = Map.of(
                "success", closed,
                "message", closed ? "Connection closed successfully" : "Connection not found"
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error closing connection {}: {}", connectionName, e.getMessage(), e);
            Map<String, Object> errorResponse = Map.of(
                "success", false,
                "error", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Close all connections
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> closeAllConnections() {
        logger.info("Closing all connections");
        
        try {
            connectionService.closeAllConnections();
            
            Map<String, Object> response = Map.of(
                "success", true,
                "message", "All connections closed successfully"
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error closing all connections: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = Map.of(
                "success", false,
                "error", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get connection details including SSL status
     */
    @GetMapping("/{connectionName}/details")
    public ResponseEntity<Map<String, Object>> getConnectionDetails(@PathVariable String connectionName) {
        try {
            boolean isSSL = connectionService.isSSLEnabled(connectionName);
            
            Map<String, Object> response = Map.of(
                "success", true,
                "connectionName", connectionName,
                "isSSL", isSSL
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting connection details for {}: {}", connectionName, e.getMessage(), e);
            Map<String, Object> errorResponse = Map.of(
                "success", false,
                "error", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
} 