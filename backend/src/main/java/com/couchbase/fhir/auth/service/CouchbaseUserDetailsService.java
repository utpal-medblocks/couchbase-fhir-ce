package com.couchbase.fhir.auth.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.users.model.User;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom UserDetailsService for OAuth 2.0 authentication using Couchbase
 * 
 * Authenticates users from fhir.Admin.users collection for:
 * - SMART on FHIR OAuth flows (authorization_code grant)
 * - OAuth consent screens
 * 
 * User Requirements:
 * - User MUST exist in fhir.Admin.users collection
 * - User MUST have status="active"
 * - User can have role="admin", "developer", or "smart_user"
 * - User MUST have authMethod="local" (password authentication)
 * 
 * Note: This is for OAuth END-USER authentication, not client credentials.
 * The Admin UI uses a separate authentication flow (AuthController).
 */
@Service("couchbaseUserDetailsService")
public class CouchbaseUserDetailsService implements UserDetailsService {
    
    private static final Logger logger = LoggerFactory.getLogger(CouchbaseUserDetailsService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "users";
    
    @Autowired
    private ConnectionService connectionService;
    
    /**
     * Load user by username (email) for OAuth authentication
     * 
     * @param username User's email address (used as username)
     * @return UserDetails object for Spring Security
     * @throws UsernameNotFoundException if user not found or invalid
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        logger.debug("🔐 [OAuth] Loading user for authentication: {}", username);
        
        try {
            // Get user from Couchbase
            Collection usersCollection = getUsersCollection();
            User user = usersCollection.get(username).contentAs(User.class);
            
            // Validate user status
            if (!"active".equals(user.getStatus())) {
                logger.warn("⚠️ [OAuth] User '{}' is not active (status: {})", username, user.getStatus());
                throw new UsernameNotFoundException("User account is not active");
            }
            
            // Validate authentication method
            if (!"local".equals(user.getAuthMethod())) {
                logger.warn("⚠️ [OAuth] User '{}' does not use local authentication (authMethod: {})", 
                    username, user.getAuthMethod());
                throw new UsernameNotFoundException("User does not support password authentication");
            }
            
            // Validate password hash exists
            if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
                logger.warn("⚠️ [OAuth] User '{}' has no password configured", username);
                throw new UsernameNotFoundException("User password not configured");
            }
            
            // Validate role
            String role = user.getRole();
            if (role == null || role.isEmpty()) {
                logger.warn("⚠️ [OAuth] User '{}' has no role assigned", username);
                throw new UsernameNotFoundException("User role not configured");
            }
            
            // Check if user is allowed to use OAuth (all roles except explicitly restricted ones)
            // Note: "smart_user" role CAN use OAuth for SMART apps
            // Only local auth is restricted for smart_user in the Admin UI, not OAuth
            
            // Update last login timestamp (async, don't block authentication)
            updateLastLogin(username);
            
            // Build authorities based on role
            List<GrantedAuthority> authorities = buildAuthorities(user);
            
            logger.info("✅ [OAuth] User '{}' authenticated successfully (role: {})", username, role);
            
            // Return Spring Security UserDetails
            return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash()) // BCrypt hash from database
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(!"active".equals(user.getStatus()))
                .build();
                
        } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
            logger.warn("⚠️ [OAuth] User '{}' not found in fhir.Admin.users", username);
            throw new UsernameNotFoundException("User not found: " + username);
        } catch (UsernameNotFoundException e) {
            // Re-throw validation errors
            throw e;
        } catch (Exception e) {
            logger.error("❌ [OAuth] Failed to load user '{}': {}", username, e.getMessage(), e);
            throw new UsernameNotFoundException("Failed to load user: " + username, e);
        }
    }
    
    /**
     * Build Spring Security authorities based on user role
     */
    private List<GrantedAuthority> buildAuthorities(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        
        // Add role-based authority
        String role = user.getRole();
        if (role != null) {
            // Spring Security expects "ROLE_" prefix
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        }
        
        // Note: Scopes are NOT added as authorities here
        // For OAuth flows, scopes come from the registered client and authorization request
        // For Admin UI, scopes are determined by role (admin: system/user, developer: user only)
        
        return authorities;
    }
    
    /**
     * Update user's last login timestamp
     * Runs asynchronously to avoid blocking authentication
     */
    private void updateLastLogin(String userId) {
        try {
            // Update in a separate thread to avoid blocking
            new Thread(() -> {
                try {
                    // Use N1QL subdoc update to only modify lastLogin field
                    String updateSql = String.format(
                        "UPDATE `%s`.`%s`.`%s` SET lastLogin = $timestamp WHERE META().id = $userId",
                        BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME
                    );
                    
                    Cluster cluster = connectionService.getConnection("default");
                    cluster.query(updateSql, 
                        com.couchbase.client.java.query.QueryOptions.queryOptions()
                            .parameters(com.couchbase.client.java.json.JsonObject.create()
                                .put("timestamp", Instant.now().toString())
                                .put("userId", userId)
                            )
                    );
                    
                    logger.debug("✅ Updated lastLogin for user: {}", userId);
                } catch (Exception e) {
                    // Log but don't fail - this is non-critical
                    logger.warn("⚠️ Failed to update lastLogin for user '{}': {}", userId, e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            logger.warn("⚠️ Failed to start lastLogin update thread: {}", e.getMessage());
        }
    }
    
    /**
     * Get the Couchbase users collection
     */
    private Collection getUsersCollection() {
        Cluster cluster = connectionService.getConnection("default");
        return cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
    }
}

