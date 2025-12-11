package com.couchbase.fhir.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * Provides a clear error page for OAuth authorization errors (e.g., invalid redirect_uri).
 * Ensures users don't get silently routed to the Admin UI when the authorize request is invalid.
 */
@ControllerAdvice
public class OAuthErrorController {
    private static final Logger logger = LoggerFactory.getLogger(OAuthErrorController.class);

    @ExceptionHandler(OAuth2AuthenticationException.class)
    public ModelAndView handleOAuth2AuthException(OAuth2AuthenticationException ex, Model model) {
        String errorCode = ex.getError().getErrorCode();
        String description = ex.getError().getDescription();
        logger.warn("OAuth2AuthenticationException: code={} desc={}", errorCode, description);

        model.addAttribute("errorCode", errorCode);
        model.addAttribute("errorDescription", description != null ? description : "Authorization request is invalid.");

        ModelAndView mav = new ModelAndView("oauth-error");
        mav.setStatus(HttpStatus.BAD_REQUEST);
        return mav;
    }
}
