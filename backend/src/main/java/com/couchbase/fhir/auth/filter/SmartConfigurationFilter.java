package com.couchbase.fhir.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to intercept SMART configuration requests BEFORE they reach HAPI FHIR servlet.
 * 
 * This filter handles:
 * - /.well-known/smart-configuration
 * - /fhir/.well-known/smart-configuration
 * 
 * Without this filter, HAPI FHIR would treat .well-known as a resource type and require authentication.
 * The filter returns the SMART configuration JSON directly and prevents further processing.
 */
@Component
@Order(1) // Run early, before HAPI servlet
public class SmartConfigurationFilter extends OncePerRequestFilter {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartConfigurationFilter.class);
    
    @Autowired
    private com.couchbase.fhir.auth.controller.SmartConfigurationController smartConfigController;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String requestURI = request.getRequestURI();
        
        // Check if this is a SMART configuration request
        if (requestURI.endsWith("/.well-known/smart-configuration")) {
            logger.debug("🔍 Intercepting SMART configuration request: {}", requestURI);
            
            try {
                // Call the controller to get the configuration
                var configResponse = smartConfigController.getSmartConfiguration();
                
                // Write JSON response
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setStatus(HttpServletResponse.SC_OK);
                
                // Add CORS headers
                response.setHeader("Access-Control-Allow-Origin", "*");
                response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers", "*");
                
                // Write response body
                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.writeValue(response.getWriter(), configResponse.getBody());
                
                logger.debug("✅ Returned SMART configuration for: {}", requestURI);
                
                // Stop filter chain - don't pass to HAPI servlet
                return;
                
            } catch (Exception e) {
                logger.error("❌ Error returning SMART configuration: {}", e.getMessage(), e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to retrieve SMART configuration");
                return;
            }
        }
        
        // Not a .well-known request - continue to next filter/servlet
        filterChain.doFilter(request, response);
    }
}

