package com.couchbase.admin.connections.controller;

import com.couchbase.admin.connections.model.Connection;
import com.couchbase.admin.connections.service.ConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/connections")
@CrossOrigin(origins = "*")
public class ConnectionController {

    @Autowired
    private ConnectionService connectionService;

    @GetMapping
    public ResponseEntity<List<Connection>> getAllConnections() {
        List<Connection> connections = connectionService.getAllConnections();
        return ResponseEntity.ok(connections);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Connection> getConnectionById(@PathVariable String id) {
        Connection connection = connectionService.getConnectionById(id);
        if (connection != null) {
            return ResponseEntity.ok(connection);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<Connection> createConnection(@RequestBody Connection connection) {
        Connection createdConnection = connectionService.createConnection(connection);
        return ResponseEntity.ok(createdConnection);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConnection(@PathVariable String id) {
        boolean deleted = connectionService.deleteConnection(id);
        if (deleted) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Connection connection) {
        boolean isConnected = connectionService.testConnection(connection);
        Map<String, Object> response = new HashMap<>();
        response.put("success", isConnected);
        response.put("message", isConnected ? "Connection successful" : "Connection failed");
        return ResponseEntity.ok(response);
    }
} 