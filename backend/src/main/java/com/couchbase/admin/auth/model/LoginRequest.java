package com.couchbase.admin.auth.model;

import lombok.Data;

/**
 * Request DTO for admin login
 */
@Data
public class LoginRequest {
    private String email;
    private String password;
}

