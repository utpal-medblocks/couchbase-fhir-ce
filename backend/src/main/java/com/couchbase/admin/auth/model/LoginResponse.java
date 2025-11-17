package com.couchbase.admin.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response DTO for successful login
 */
@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private UserInfo user;
}

