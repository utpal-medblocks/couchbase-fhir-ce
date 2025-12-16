package com.couchbase.fhir.auth.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Login controller for OAuth 2.0 authorization flow
 * Provides a simple HTML login page for authenticating users
 * Note: Consent page is handled by ConsentController
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(HttpServletResponse response) {
        // Prevent caching of login page to ensure users always see the latest version
        // This prevents browser from serving stale cached login pages
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        return "login";
    }
}

