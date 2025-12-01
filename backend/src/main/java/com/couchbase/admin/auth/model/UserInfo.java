package com.couchbase.admin.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User information DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {
    private String email;
    private String name;
    private String role;
    private String[] allowedScopes;
}

