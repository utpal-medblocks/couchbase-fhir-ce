package com.couchbase.admin.users.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private boolean active;
    private LocalDateTime lastLogin;
    private List<String> permissions;
} 