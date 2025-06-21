package com.couchbase.admin.audit.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    private String id;
    private String userId;
    private String action;
    private String resource;
    private String details;
    private LocalDateTime timestamp;
    private String ipAddress;
    private String userAgent;
} 