package com.couchbase.fhir.auth.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

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
}

