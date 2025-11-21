package com.couchbase.admin.auth.controller;

import com.couchbase.admin.auth.model.LoginRequest;
import com.couchbase.admin.auth.model.LoginResponse;
import com.couchbase.admin.auth.model.UserInfo;
import com.couchbase.admin.auth.util.JwtUtil;
import com.couchbase.admin.users.model.User;
import com.couchbase.admin.users.service.UserService;
import com.couchbase.common.config.AdminConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for Admin UI authentication
 * Handles login with credentials from config.yaml
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AdminConfig adminConfig;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserService userService;

    /**
     * Login endpoint for Admin UI
     * Validates credentials against config.yaml admin section
     * 
     * @param loginRequest Login credentials
     * @return JWT token and user info if successful, error otherwise
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        if (loginRequest.getEmail() == null || loginRequest.getPassword() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse("Email and password are required"));
        }

        String email = loginRequest.getEmail();
        String password = loginRequest.getPassword();

        // 1) Try authenticating against Admin.users (if database is available)
        try {
            java.util.Optional<User> userOpt = userService.getUserById(email);

            if (userOpt.isPresent()) {
                User user = userOpt.get();
                // Only support local auth users for Admin UI login
                if ("local".equals(user.getAuthMethod())) {
                    boolean passwordOk = userService.verifyPassword(user.getId(), password);
                    if (passwordOk) {
                        String displayName = user.getUsername() != null ? user.getUsername() : user.getEmail();
                        String token = jwtUtil.generateToken(user.getEmail(), displayName);
                        UserInfo userInfo = new UserInfo(user.getEmail(), displayName);
                        return ResponseEntity.ok(new LoginResponse(token, userInfo));
                    }

                    // Local user exists but password doesn't match
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(createErrorResponse("Invalid email or password"));
                }
            }
        } catch (Exception e) {
            // Silently fall through to config-based auth if database not available
            // This is expected on first startup before FHIR bucket is initialized
        }

        // 2) Fallback: Validate credentials against config.yaml
        // This allows login before bucket creation/initialization
        String configEmail = adminConfig.getEmail();
        String configPassword = adminConfig.getPassword();
        String configName = adminConfig.getName();

        if (configEmail.equals(email) && configPassword.equals(password)) {
            String token = jwtUtil.generateToken(configEmail, configName);
            UserInfo userInfo = new UserInfo(configEmail, configName);
            return ResponseEntity.ok(new LoginResponse(token, userInfo));
        }

        // Invalid credentials
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(createErrorResponse("Invalid email or password"));
    }

    /**
     * Validate token endpoint (optional, for checking if token is still valid)
     * 
     * @param authHeader Authorization header with Bearer token
     * @return User info if token is valid
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("Invalid authorization header"));
        }

        String token = authHeader.substring(7);
        
        if (jwtUtil.validateToken(token)) {
            String email = jwtUtil.extractEmail(token);
            String name = jwtUtil.extractName(token);
            UserInfo userInfo = new UserInfo(email, name);
            return ResponseEntity.ok(userInfo);
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(createErrorResponse("Invalid or expired token"));
    }

    /**
     * Helper method to create error response
     */
    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}

