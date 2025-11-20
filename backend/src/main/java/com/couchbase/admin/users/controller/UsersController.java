package com.couchbase.admin.users.controller;

import com.couchbase.admin.users.model.User;
import com.couchbase.admin.users.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for user management
 * Protected by Admin UI JWT authentication (/api/admin/*)
 */
@RestController
@RequestMapping("/api/admin/users")
public class UsersController {
    
    private static final Logger logger = LoggerFactory.getLogger(UsersController.class);
    
    @Autowired
    private UserService userService;
    
    /**
     * Get all users
     * GET /api/admin/users
     */
    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        logger.info("📋 Fetching all users");
        List<User> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }
    
    /**
     * Get user by ID
     * GET /api/admin/users/{userId}
     */
    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserById(@PathVariable String userId) {
        logger.info("🔍 Fetching user: {}", userId);
        
        return userService.getUserById(userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Create new user
     * POST /api/admin/users
     */
    @PostMapping
    public ResponseEntity<?> createUser(
            @RequestBody User user,
            Authentication authentication) {
        
        try {
            // Get current user ID from authentication
            String createdBy = authentication != null ? authentication.getName() : "system";
            
            logger.info("➕ Creating user: {} by {}", user.getId(), createdBy);
            
            User createdUser = userService.createUser(user, createdBy);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
            
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error creating user: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error creating user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create user"));
        }
    }
    
    /**
     * Update user
     * PUT /api/admin/users/{userId}
     */
    @PutMapping("/{userId}")
    public ResponseEntity<?> updateUser(
            @PathVariable String userId,
            @RequestBody User user) {
        
        try {
            logger.info("📝 Updating user: {}", userId);
            
            User updatedUser = userService.updateUser(userId, user);
            return ResponseEntity.ok(updatedUser);
            
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error updating user: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error updating user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to update user"));
        }
    }
    
    /**
     * Deactivate user (soft delete)
     * PUT /api/admin/users/{userId}/deactivate
     */
    @PutMapping("/{userId}/deactivate")
    public ResponseEntity<?> deactivateUser(@PathVariable String userId) {
        try {
            logger.info("⏸️ Deactivating user: {}", userId);
            
            userService.deactivateUser(userId);
            return ResponseEntity.ok()
                .body(Map.of("message", "User deactivated successfully"));
            
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error deactivating user: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error deactivating user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to deactivate user"));
        }
    }

    /**
     * Delete user (hard delete)
     * DELETE /api/admin/users/{userId}
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable String userId) {
        try {
            logger.info("🗑️ Deleting user: {}", userId);
            
            userService.deleteUser(userId);
            return ResponseEntity.ok()
                .body(Map.of("message", "User deleted successfully"));
            
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error deleting user: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error deleting user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to delete user"));
        }
    }
    
    /**
     * Get current user profile
     * GET /api/admin/users/me
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String userId = authentication.getName();
        logger.info("👤 Fetching current user profile: {}", userId);
        
        return userService.getUserById(userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Check if system has any users (for first-time setup)
     * GET /api/admin/users/exists
     */
    @GetMapping("/exists")
    public ResponseEntity<Map<String, Boolean>> checkUsersExist() {
        boolean hasUsers = userService.hasUsers();
        return ResponseEntity.ok(Map.of("hasUsers", hasUsers));
    }
}

