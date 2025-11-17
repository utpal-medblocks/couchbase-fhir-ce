package com.couchbase.admin.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * User information DTO
 */
@Data
@AllArgsConstructor
public class UserInfo {
    private String email;
    private String name;
}

