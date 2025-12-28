package com.couchbase.admin.users.service;

import com.couchbase.admin.users.model.User;

import java.util.List;
import java.util.Optional;

public interface KeycloakUserManager {
    User createUser(User user, String createdBy);
    Optional<User> getUserById(String userId);
    Optional<User> getUserByEmail(String email);
    List<User> getAllUsers();
    User updateUser(String userId, User updatedUser);
    void deactivateUser(String userId);
    void deleteUser(String userId);
    void updateLastLogin(String userId);
}
