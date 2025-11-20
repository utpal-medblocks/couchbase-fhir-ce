package com.couchbase.admin.users.service;

import com.couchbase.admin.users.model.User;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing users in Couchbase
 * Users are stored in: fhir.Admin.users collection
 */
@Service
public class UserService {
    
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "users";
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    /**
     * Get Couchbase collection for users
     */
    private Collection getUsersCollection() {
        Cluster cluster = connectionService.getConnection("default");
        return cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
    }
    
    /**
     * Create a new user
     * @param user User to create
     * @param createdBy ID of user creating this user
     * @return Created user
     */
    public User createUser(User user, String createdBy) {
        logger.info("👤 Creating user: {} ({})", user.getId(), user.getEmail());
        
        // Validate required fields
        if (user.getId() == null || user.getId().isEmpty()) {
            throw new IllegalArgumentException("User ID is required");
        }
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }
        
        // Validate password for local auth users
        if ("local".equals(user.getAuthMethod())) {
            if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
                throw new IllegalArgumentException("Password is required for local authentication");
            }
        }
        
        // Check if user already exists
        if (getUserById(user.getId()).isPresent()) {
            throw new IllegalArgumentException("User with ID '" + user.getId() + "' already exists");
        }
        
        // Set metadata
        user.setCreatedBy(createdBy);
        user.setCreatedAt(Instant.now());
        
        // Hash password if provided
        if (user.getPasswordHash() != null && !user.getPasswordHash().isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        }
        
        // Save to Couchbase
        Collection collection = getUsersCollection();
        collection.insert(user.getId(), user);
        
        logger.info("✅ User created: {}", user.getId());
        return user;
    }
    
    /**
     * Get user by ID
     * @param userId User ID
     * @return User if found
     */
    public Optional<User> getUserById(String userId) {
        try {
            Collection collection = getUsersCollection();
            User user = collection.get(userId).contentAs(User.class);
            return Optional.of(user);
        } catch (Exception e) {
            logger.debug("User not found: {}", userId);
            return Optional.empty();
        }
    }
    
    /**
     * Get user by email
     * @param email User email
     * @return User if found
     */
    public Optional<User> getUserByEmail(String email) {
        try {
            Cluster cluster = connectionService.getConnection("default");
            String query = "SELECT u.* FROM `" + BUCKET_NAME + "`.`" + SCOPE_NAME + "`.`" + COLLECTION_NAME + "` u " +
                          "WHERE u.email = $email AND u.status = 'active'";
            
            QueryResult result = cluster.query(query, 
                com.couchbase.client.java.query.QueryOptions.queryOptions()
                    .parameters(com.couchbase.client.java.json.JsonObject.create().put("email", email))
            );
            
            if (result.rowsAsObject().isEmpty()) {
                return Optional.empty();
            }
            
            return Optional.of(result.rowsAs(User.class).get(0));
        } catch (Exception e) {
            logger.error("Error finding user by email: {}", email, e);
            return Optional.empty();
        }
    }
    
    /**
     * Get all users
     * @return List of all users
     */
    public List<User> getAllUsers() {
        try {
            Cluster cluster = connectionService.getConnection("default");
            String query = "SELECT u.* FROM `" + BUCKET_NAME + "`.`" + SCOPE_NAME + "`.`" + COLLECTION_NAME + "` u " +
                          "ORDER BY u.createdAt DESC";
            
            QueryResult result = cluster.query(query);
            return result.rowsAs(User.class);
        } catch (Exception e) {
            logger.error("Error fetching all users", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Update user
     * @param userId User ID
     * @param updatedUser Updated user data
     * @return Updated user
     */
    public User updateUser(String userId, User updatedUser) {
        logger.info("📝 Updating user: {}", userId);
        
        // Get existing user
        User existingUser = getUserById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        
        // Update fields (don't allow changing ID or email)
        if (updatedUser.getUsername() != null) {
            existingUser.setUsername(updatedUser.getUsername());
        }
        if (updatedUser.getRole() != null) {
            existingUser.setRole(updatedUser.getRole());
        }
        if (updatedUser.getStatus() != null) {
            existingUser.setStatus(updatedUser.getStatus());
        }
        if (updatedUser.getPasswordHash() != null && !updatedUser.getPasswordHash().isEmpty()) {
            existingUser.setPasswordHash(passwordEncoder.encode(updatedUser.getPasswordHash()));
        }
        
        // Save updated user
        Collection collection = getUsersCollection();
        collection.replace(userId, existingUser);
        
        logger.info("✅ User updated: {}", userId);
        return existingUser;
    }
    
    /**
     * Deactivate user (soft delete - set status to inactive)
     * @param userId User ID
     */
    public void deactivateUser(String userId) {
        logger.info("⏸️ Deactivating user: {}", userId);
        
        User user = getUserById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        
        user.setStatus("inactive");
        
        Collection collection = getUsersCollection();
        collection.replace(userId, user);
        
        logger.info("✅ User deactivated: {}", userId);
    }

    /**
     * Delete user (hard delete - remove document)
     * @param userId User ID
     */
    public void deleteUser(String userId) {
        logger.info("🗑️ Hard deleting user: {}", userId);
        
        // Check existence
        if (getUserById(userId).isEmpty()) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        
        Collection collection = getUsersCollection();
        collection.remove(userId);
        
        logger.info("✅ User deleted (hard): {}", userId);
    }
    
    /**
     * Update last login timestamp
     * @param userId User ID
     */
    public void updateLastLogin(String userId) {
        try {
            User user = getUserById(userId).orElse(null);
            if (user != null) {
                user.setLastLogin(Instant.now());
                Collection collection = getUsersCollection();
                collection.replace(userId, user);
                logger.debug("Updated last login for user: {}", userId);
            }
        } catch (Exception e) {
            logger.error("Error updating last login for user: {}", userId, e);
        }
    }
    
    /**
     * Verify user password
     * @param userId User ID
     * @param rawPassword Plain text password
     * @return true if password matches
     */
    public boolean verifyPassword(String userId, String rawPassword) {
        User user = getUserById(userId).orElse(null);
        if (user == null || user.getPasswordHash() == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }
    
    /**
     * Check if any users exist (for first-time setup detection)
     * @return true if at least one user exists
     */
    public boolean hasUsers() {
        try {
            Cluster cluster = connectionService.getConnection("default");
            String query = "SELECT COUNT(*) as count FROM `" + BUCKET_NAME + "`.`" + SCOPE_NAME + "`.`" + COLLECTION_NAME + "`";
            
            QueryResult result = cluster.query(query);
            int count = result.rowsAsObject().get(0).getInt("count");
            return count > 0;
        } catch (Exception e) {
            logger.error("Error checking if users exist", e);
            return false;
        }
    }
}

