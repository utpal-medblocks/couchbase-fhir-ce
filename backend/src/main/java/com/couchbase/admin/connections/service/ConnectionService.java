package com.couchbase.admin.connections.service;

import com.couchbase.admin.connections.model.Connection;
import com.couchbase.admin.connections.model.ConnectionRequest;
import com.couchbase.admin.connections.model.ConnectionResponse;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.ArrayList;

@Service
public class ConnectionService {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionService.class);
    
    // Store active connections
    private final Map<String, Cluster> activeConnections = new ConcurrentHashMap<>();
    
    /**
     * Create and store a connection for later use
     */
    public ConnectionResponse createConnection(ConnectionRequest request) {
        logger.info("Creating connection: {}", request.getName());
        
        try {
            ClusterOptions options = ClusterOptions.clusterOptions(request.getUsername(), request.getPassword())
                    .environment(env -> {
                        // Set reasonable timeouts for good error detection
                        env.timeoutConfig().connectTimeout(Duration.ofSeconds(10));
                        env.timeoutConfig().queryTimeout(Duration.ofSeconds(30));
                        env.timeoutConfig().managementTimeout(Duration.ofSeconds(15));
                    });
            
            Cluster cluster = Cluster.connect(request.getConnectionString(), options);
            
            // IMPORTANT: Test the connection immediately with a timeout
            try {
                // Try to get cluster info to validate the connection
                cluster.buckets().getAllBuckets();
                logger.info("Connection validation successful for: {}", request.getName());
            } catch (Exception validationError) {
                // Close the cluster immediately if validation fails
                try {
                    cluster.disconnect();
                } catch (Exception disconnectError) {
                    logger.warn("Error disconnecting failed cluster: {}", disconnectError.getMessage());
                }
                
                // Provide user-friendly error messages based on the error type
                String userFriendlyMessage = getUserFriendlyErrorMessage(validationError);
                logger.error("Connection validation failed for {}: {}", request.getName(), validationError.getMessage());
                return ConnectionResponse.failure("Connection failed", userFriendlyMessage);
            }
            
            // Close existing connection if it exists
            closeConnection(request.getName());
            
            // Store the new connection
            activeConnections.put(request.getName(), cluster);
            logger.info("Successfully created and stored connection: {}", request.getName());
            
            // Extract cluster name from connection string for response
            String clusterName = extractClusterName(request.getConnectionString());
            
            return ConnectionResponse.successWithClusterName("Connection created successfully", clusterName);
            
        } catch (Exception e) {
            logger.error("Error creating connection {}: {}", request.getName(), e.getMessage(), e);
            
            // Check if it's an UnknownHostException specifically
            if (e.getCause() instanceof java.net.UnknownHostException || 
                e.getMessage().contains("UnknownHostException")) {
                return ConnectionResponse.failure("Connection failed", "Invalid hostname - please check the server address");
            }
            
            return ConnectionResponse.failure("Failed to create connection", getUserFriendlyErrorMessage(e));
        }
    }
    
    /**
     * Get an active connection by name
     */
    public Cluster getConnection(String connectionName) {
        return activeConnections.get(connectionName);
    }
    
    /**
     * Close a specific connection
     */
    public boolean closeConnection(String connectionName) {
        Cluster cluster = activeConnections.remove(connectionName);
        if (cluster != null) {
            try {
                cluster.disconnect();
                logger.info("Closed connection: {}", connectionName);
                return true;
            } catch (Exception e) {
                logger.error("Error closing connection {}: {}", connectionName, e.getMessage());
                return false;
            }
        }
        return false;
    }
    
    /**
     * Get list of active connection names
     */
    public List<String> getActiveConnections() {
        return activeConnections.keySet().stream()
                .collect(Collectors.toList());
    }
    
    /**
     * Close all connections (useful for shutdown)
     */
    public void closeAllConnections() {
        logger.info("Closing all active connections");
        activeConnections.forEach((name, cluster) -> {
            try {
                cluster.disconnect();
                logger.info("Closed connection: {}", name);
            } catch (Exception e) {
                logger.error("Error closing connection {}: {}", name, e.getMessage());
            }
        });
        activeConnections.clear();
    }
    
    /**
     * Extract cluster name from connection string
     */
    private String extractClusterName(String connectionString) {
        try {
            // Remove protocol prefix
            String cleanString = connectionString.replaceAll("^couchbases?://", "");
            
            // For Capella, extract the cluster identifier
            if (cleanString.contains("cloud.couchbase.com")) {
                String[] parts = cleanString.split("\\.");
                if (parts.length > 0) {
                    return parts[0]; // Return the cluster identifier
                }
            }
            
            // For self-managed, use the hostname
            String[] parts = cleanString.split(":");
            return parts[0];
            
        } catch (Exception e) {
            logger.warn("Could not extract cluster name from connection string: {}", connectionString);
            return "Unknown Cluster";
        }
    }
    
    // Legacy methods for compatibility
    public List<Connection> getAllConnections() {
        // TODO: Implement actual connection retrieval logic
        return new ArrayList<>();
    }
    
    public Connection getConnectionById(String id) {
        // TODO: Implement actual connection retrieval logic
        return null;
    }
    
    public Connection createConnection(Connection connection) {
        // TODO: Implement actual connection creation logic
        return connection;
    }
    
    public boolean deleteConnection(String id) {
        // TODO: Implement actual connection deletion logic
        return true;
    }
    
    public boolean testConnection(Connection connection) {
        // TODO: Implement actual connection testing logic
        return true;
    }
    
    /**
     * Convert technical error messages to user-friendly ones
     */
    private String getUserFriendlyErrorMessage(Exception error) {
        String message = error.getMessage();
        
        // Check for authentication errors first (even if they appear in timeout messages)
        if (message.contains("AuthenticationFailureException") || 
            message.contains("authentication failed") || 
            message.contains("AUTHENTICATION_ERROR") ||
            message.contains("check username and password")) {
            return "Authentication failed - please check username and password";
        }
        
        if (message.contains("TIMEOUT") || message.contains("timeout")) {
            // Check if the timeout was caused by authentication issues
            if (message.contains("AUTHENTICATION_ERROR")) {
                return "Authentication failed - please check username and password";
            }
            return "Connection failed - please check the hostname and ensure the Couchbase server is running";
        }
        
        if (message.contains("UnknownHostException") || message.contains("nodename nor servname provided")) {
            return "Invalid hostname - please check the server address";
        }
        
        if (message.contains("SSL") || message.contains("TLS")) {
            return "SSL/TLS connection error - please check SSL settings";
        }
        
        if (message.contains("CONNECTION_REFUSED") || message.contains("refused")) {
            return "Connection refused - please check if the server is running and accessible";
        }
        
        // Default to a generic but helpful message
        return "Unable to connect to Couchbase server - please check hostname, credentials, and server status";
    }
} 