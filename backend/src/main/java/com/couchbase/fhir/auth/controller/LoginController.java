package com.couchbase.fhir.auth.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.ui.Model;

/**
 * Login controller for OAuth 2.0 authorization flow
 * Provides a simple HTML login page for authenticating users
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * Consent page mapped for Spring Authorization Server.
     * The framework populates the model with attributes like clientId, clientName, principalName,
     * scopes, redirect_uri, state, code_challenge, etc. We simply return the Thymeleaf view.
     */
    @GetMapping("/consent")
    public String consent(Model model,
                          @RequestParam(required = false) String client_id,
                          @RequestParam(required = false) String state,
                          @RequestParam(required = false) String redirect_uri) {
        // Attributes are provided by SAS; optionally log for diagnostics
        return "consent";
    }
}

