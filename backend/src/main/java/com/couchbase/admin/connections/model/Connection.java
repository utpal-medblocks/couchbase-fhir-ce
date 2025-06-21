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
    private String host;
    private int port;
    private String username;
    private String status;
    private long lastConnected;
    private String type; // "couchbase", "fhir", etc.
} 