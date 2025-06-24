package com.couchbase.admin.connections.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Connection {
    private String id;
    private String name;
    private String connectionString;
    private String username;
    private String password;
    private String bucket;
    private String scope;
    private String collection;
    private String serverType; // "Server" or "Capella"
    private boolean sslEnabled;
    private String status;
    private long lastConnected;
    private String type; // "couchbase", "fhir", etc.
} 