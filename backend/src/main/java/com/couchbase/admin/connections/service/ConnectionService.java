package com.couchbase.admin.connections.service;

import com.couchbase.admin.connections.model.ConnectionRequest;
import com.couchbase.admin.connections.model.ConnectionResponse;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.env.ClusterEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ConnectionService {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionService.class);
    
    // SDK Configuration Parameters with sensible defaults for high-concurrency FHIR workloads
    @Value("${couchbase.sdk.max-http-connections:128}")
    private int maxHttpConnections;
    
    @Value("${couchbase.sdk.num-kv-connections:8}")
    private int numKvConnections;
    
    @Value("${couchbase.sdk.query-timeout-seconds:30}")
    private int queryTimeoutSeconds;
    
    @Value("${couchbase.sdk.search-timeout-seconds:30}")
    private int searchTimeoutSeconds;
    
    @Value("${couchbase.sdk.kv-timeout-seconds:10}")
    private int kvTimeoutSeconds;
    
    @Value("${couchbase.sdk.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;
    
    @Value("${couchbase.sdk.disconnect-timeout-seconds:10}")
    private int disconnectTimeoutSeconds;
    
    @Value("${couchbase.sdk.enable-mutation-tokens:true}")
    private boolean enableMutationTokens;
    
    // Store active connections
    private final Map<String, Cluster> activeConnections = new ConcurrentHashMap<>();
    
    // Store connection details including SSL status
    private final Map<String, ConnectionDetails> connectionDetails = new ConcurrentHashMap<>();
    
    // Store last connection error for frontend
    private volatile String lastConnectionError = null;
    
    // Inner class to store connection details
    public static class ConnectionDetails {
        private final boolean sslEnabled;
        private final String connectionString;
        private final String username;
        private final String password;
        private final String serverType;
        
        public ConnectionDetails(boolean sslEnabled, String connectionString, String username, String password, String serverType) {
            this.sslEnabled = sslEnabled;
            this.connectionString = connectionString;
            this.username = username;
            this.password = password;
            this.serverType = serverType;
        }
        
        public boolean isSslEnabled() { return sslEnabled; }
        public String getConnectionString() { return connectionString; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getServerType() { return serverType; }
    }
    
    /**
     * Create and store a connection for later use
     */
    public ConnectionResponse createConnection(ConnectionRequest request) {
        logger.info("Creating connection: {}", request.getName());
        
        try {
            // Configure cluster environment with comprehensive SDK tuning for high-concurrency FHIR workloads
            ClusterEnvironment env = ClusterEnvironment.builder()
                .timeoutConfig(timeoutConfig -> timeoutConfig
                    .queryTimeout(Duration.ofSeconds(queryTimeoutSeconds))
                    .searchTimeout(Duration.ofSeconds(searchTimeoutSeconds))
                    .kvTimeout(Duration.ofSeconds(kvTimeoutSeconds))
                    .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .disconnectTimeout(Duration.ofSeconds(disconnectTimeoutSeconds)))
                .ioConfig(io -> io
                    .maxHttpConnections(maxHttpConnections)
                    .numKvConnections(numKvConnections)
                    .enableMutationTokens(enableMutationTokens)
                    )
                .build();
            
            ClusterOptions options = ClusterOptions.clusterOptions(request.getUsername(), request.getPassword())
                    .environment(env);
                    
            logger.info("🔧 SDK Configuration Applied:");
            logger.info("   - maxHttpConnections: {} (couchbase.sdk.max-http-connections)", maxHttpConnections);
            logger.info("   - numKvConnections: {} (couchbase.sdk.num-kv-connections)", numKvConnections);
            logger.info("   - queryTimeout: {}s (couchbase.sdk.query-timeout-seconds)", queryTimeoutSeconds);
            logger.info("   - searchTimeout: {}s (couchbase.sdk.search-timeout-seconds)", searchTimeoutSeconds);
            logger.info("   - kvTimeout: {}s (couchbase.sdk.kv-timeout-seconds)", kvTimeoutSeconds);
            logger.info("   - connectTimeout: {}s (couchbase.sdk.connect-timeout-seconds)", connectTimeoutSeconds);
            logger.info("   - enableMutationTokens: {} (couchbase.sdk.enable-mutation-tokens)", enableMutationTokens);
            
            Cluster cluster = Cluster.connect(request.getConnectionString(), options);
            
            // IMPORTANT: Test the connection immediately with a reasonable timeout
            try {
                logger.info("🔍 Validating connection with 10-second timeout...");
                
                // Use CompletableFuture with timeout to prevent hanging
                java.util.concurrent.CompletableFuture<Void> validationFuture = 
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            cluster.buckets().getAllBuckets();
                        } catch (RuntimeException e) {
                            throw e;
                        }
                    });
                
                // Wait with reasonable timeout - if this times out, it's likely an auth issue
                validationFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
                
                logger.info("✅ Connection validation successful for: {}", request.getName());
            } catch (java.util.concurrent.TimeoutException timeoutError) {
                // Close the cluster immediately if validation times out
                try {
                    cluster.disconnect();
                } catch (Exception disconnectError) {
                    logger.warn("Error disconnecting timed-out cluster: {}", disconnectError.getMessage());
                }
                
                logger.error("⏰ Connection validation timed out for {}: likely authentication failure", request.getName());
                String errorMsg = "Authentication failed - please check username and password in config.yaml";
                this.lastConnectionError = errorMsg;
                return ConnectionResponse.failure("Connection failed", errorMsg);
            } catch (java.util.concurrent.ExecutionException executionError) {
                // Close the cluster immediately if validation fails
                try {
                    cluster.disconnect();
                } catch (Exception disconnectError) {
                    logger.warn("Error disconnecting failed cluster: {}", disconnectError.getMessage());
                }
                
                Throwable cause = executionError.getCause();
                String userFriendlyMessage = getUserFriendlyErrorMessage(new Exception(cause));
                logger.error("❌ Connection validation failed for {}: {}", request.getName(), cause.getMessage());
                this.lastConnectionError = userFriendlyMessage;
                return ConnectionResponse.failure("Connection failed", userFriendlyMessage);
            } catch (Exception validationError) {
                // Close the cluster immediately if validation fails
                try {
                    cluster.disconnect();
                } catch (Exception disconnectError) {
                    logger.warn("Error disconnecting failed cluster: {}", disconnectError.getMessage());
                }
                
                String userFriendlyMessage = getUserFriendlyErrorMessage(validationError);
                logger.error("❌ Connection validation failed for {}: {}", request.getName(), validationError.getMessage());
                this.lastConnectionError = userFriendlyMessage;
                return ConnectionResponse.failure("Connection failed", userFriendlyMessage);
            }
            
            // Close existing connection if it exists
            closeConnection(request.getName());
            
            // Store the new connection
            activeConnections.put(request.getName(), cluster);
            
            // Store connection details including SSL status and server type
            connectionDetails.put(request.getName(), new ConnectionDetails(request.isSslEnabled(), request.getConnectionString(), request.getUsername(), request.getPassword(), request.getServerType()));
            
            logger.info("Successfully created and stored connection: {}", request.getName());
            
            // Clear any previous connection error on success
            this.lastConnectionError = null;
            
            // Extract cluster name from connection string for response
            String clusterName = extractClusterName(request.getConnectionString());
            
            return ConnectionResponse.successWithClusterName("Connection created successfully", clusterName);
            
        } catch (Exception e) {
            logger.error("Error creating connection {}: {}", request.getName(), e.getMessage(), e);
            
            // Check if it's an UnknownHostException specifically
            if (e.getCause() instanceof java.net.UnknownHostException || 
                e.getMessage().contains("UnknownHostException")) {
                String errorMsg = "Invalid hostname - please check the server address";
                this.lastConnectionError = errorMsg;
                return ConnectionResponse.failure("Connection failed", errorMsg);
            }
            
            String errorMsg = getUserFriendlyErrorMessage(e);
            this.lastConnectionError = errorMsg;
            return ConnectionResponse.failure("Failed to create connection", errorMsg);
        }
    }
    
    /**
     * Get an active connection by name
     */
    public Cluster getConnection(String connectionName) {
        Cluster cluster = activeConnections.get(connectionName);
        if (cluster != null) {
            logger.debug("Reusing existing connection: {} (total active: {})", connectionName, activeConnections.size());
        } else {
            logger.warn("No active connection found for: {} (available: {})", connectionName, activeConnections.keySet());
        }
        return cluster;
    }
    
    /**
     * Get SSL status for a connection
     */
    public boolean isSSLEnabled(String connectionName) {
        ConnectionDetails details = connectionDetails.get(connectionName);
        return details != null && details.isSslEnabled();
    }
    
    /**
     * Get connection details for REST API calls
     */
    public ConnectionDetails getConnectionDetails(String connectionName) {
        return connectionDetails.get(connectionName);
    }
    
    /**
     * Extract hostname from connection string
     */
    public String getHostname(String connectionName) {
        ConnectionDetails details = connectionDetails.get(connectionName);
        if (details == null) {
            return null;
        }
        
        try {
            String connectionString = details.getConnectionString();
            // Remove protocol prefix
            String cleanString = connectionString.replaceAll("^couchbases?://", "");
            // Extract hostname (before any port or path)
            String[] parts = cleanString.split("[:/]");
            return parts[0];
        } catch (Exception e) {
            logger.warn("Could not extract hostname from connection string: {}", details.getConnectionString());
            return "localhost";
        }
    }
    
    /**
     * Extract port from connection string
     */
    public int getPort(String connectionName) {
        ConnectionDetails details = connectionDetails.get(connectionName);
        if (details == null) {
            return 8091; // default Couchbase REST port
        }
        
        try {
            String connectionString = details.getConnectionString();
            // Remove protocol prefix
            String cleanString = connectionString.replaceAll("^couchbases?://", "");
            // Extract port if present
            String[] parts = cleanString.split("[:/]");
            if (parts.length > 1 && parts[1].matches("\\d+")) {
                return Integer.parseInt(parts[1]);
            }
            return 8091; // default Couchbase REST port
        } catch (Exception e) {
            logger.warn("Could not extract port from connection string: {}", details.getConnectionString());
            return 8091;
        }
    }
    
    /**
     * Close a specific connection
     */
    public boolean closeConnection(String connectionName) {
        Cluster cluster = activeConnections.remove(connectionName);
        connectionDetails.remove(connectionName); // Clean up connection details
        
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
     * Get the last connection error for frontend display
     */
    public String getLastConnectionError() {
        return this.lastConnectionError;
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
        connectionDetails.clear(); // Clean up all connection details
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