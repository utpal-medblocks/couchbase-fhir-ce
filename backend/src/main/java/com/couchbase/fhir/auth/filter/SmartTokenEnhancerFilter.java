package com.couchbase.fhir.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Filter that intercepts /oauth2/token responses and injects the "patient" claim
 * into the top-level JSON response (not just the JWT).
 * 
 * This is required for SMART on FHIR compliance - Inferno expects the patient ID
 * at the top level of the token response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SmartTokenEnhancerFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(SmartTokenEnhancerFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info("🔧 [SMART-ENHANCER] Initialized - OAuth token responses will be enhanced with patient context");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();

        // Only intercept POST requests to /oauth2/token
        if ("POST".equalsIgnoreCase(method) && uri.endsWith("/oauth2/token")) {
            logger.debug("🎫 [SMART-ENHANCER] Intercepting token endpoint: {} {}", method, uri);
            
            // Wrap response to capture output
            ResponseCaptureWrapper responseWrapper = new ResponseCaptureWrapper(httpResponse);
            
            // Continue filter chain
            chain.doFilter(request, responseWrapper);
            
            // Get the captured response
            byte[] responseData = responseWrapper.getCapturedData();
            String responseBody = new String(responseData, StandardCharsets.UTF_8);
            
            try {
                // Parse JSON response
                @SuppressWarnings("unchecked")
                Map<String, Object> tokenResponse = objectMapper.readValue(responseBody, Map.class);
                
                // Check if this is an error response
                if (tokenResponse.containsKey("error")) {
                    String error = (String) tokenResponse.get("error");
                    
                    // OAuth 2.0 spec: invalid_grant and other errors should return 400 Bad Request
                    int errorStatus = HttpServletResponse.SC_BAD_REQUEST; // 400
                    if ("invalid_client".equals(error)) {
                        errorStatus = HttpServletResponse.SC_UNAUTHORIZED; // 401
                    }
                    
                    logger.debug("⚠️ [SMART-ENHANCER] Token error '{}', returning status {}", error, errorStatus);
                    httpResponse.reset();
                    httpResponse.setStatus(errorStatus);
                    httpResponse.setContentType("application/json");
                    httpResponse.setCharacterEncoding("UTF-8");
                    httpResponse.setContentLength(responseData.length);
                    httpResponse.setHeader("Cache-Control", "no-store");
                    httpResponse.setHeader("Pragma", "no-cache");
                    httpResponse.getOutputStream().write(responseData);
                    httpResponse.getOutputStream().flush();
                    return;
                }
                
                // Check if patient claim already exists (shouldn't happen, but handle it)
                if (!tokenResponse.containsKey("patient")) {
                    // Extract from access_token JWT
                    String accessToken = (String) tokenResponse.get("access_token");
                    if (accessToken != null && accessToken.contains(".")) {
                        String[] parts = accessToken.split("\\.");
                        if (parts.length >= 2) {
                            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                            @SuppressWarnings("unchecked")
                            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
                            
                            Object patientClaim = claims.get("patient");
                            if (patientClaim != null) {
                                // Add patient to top-level response
                                tokenResponse.put("patient", patientClaim);
                                
                                // Rewrite response
                                String modifiedResponse = objectMapper.writeValueAsString(tokenResponse);
                                byte[] modifiedData = modifiedResponse.getBytes(StandardCharsets.UTF_8);
                                
                                logger.debug("✅ [SMART-ENHANCER] Token response enhanced with patient: '{}' (scope: {})", 
                                    patientClaim, tokenResponse.get("scope"));
                                
                                // Write modified response with proper headers (including OAuth security headers)
                                httpResponse.reset(); // Clear any existing data
                                httpResponse.setStatus(HttpServletResponse.SC_OK);
                                httpResponse.setContentType("application/json");
                                httpResponse.setCharacterEncoding("UTF-8");
                                httpResponse.setContentLength(modifiedData.length);
                                // OAuth 2.0 Security: Token responses must not be cached
                                httpResponse.setHeader("Cache-Control", "no-store");
                                httpResponse.setHeader("Pragma", "no-cache");
                                httpResponse.getOutputStream().write(modifiedData);
                                httpResponse.getOutputStream().flush();
                                return;
                            }
                        }
                    }
                }
                
            } catch (Exception e) {
                logger.error("❌ [SMART-ENHANCER] Error processing token response: {}", e.getMessage(), e);
            }
            
            // Write original response if no modification was made
            httpResponse.setContentType("application/json");
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setContentLength(responseData.length);
            // OAuth 2.0 Security: Token responses must not be cached
            httpResponse.setHeader("Cache-Control", "no-store");
            httpResponse.setHeader("Pragma", "no-cache");
            httpResponse.getOutputStream().write(responseData);
            httpResponse.getOutputStream().flush();
            
        } else {
            // Not a token endpoint request, pass through
            chain.doFilter(request, response);
        }
    }

    /**
     * Response wrapper that ONLY captures the response data without writing through
     */
    private static class ResponseCaptureWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        private ServletOutputStream output;
        private PrintWriter writer;

        public ResponseCaptureWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("getWriter() has already been called");
            }
            if (output == null) {
                output = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                    }

                    @Override
                    public void write(int b) throws IOException {
                        capture.write(b); // Only capture, don't write through
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        capture.write(b, off, len); // Only capture, don't write through
                    }
                };
            }
            return output;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (output != null) {
                throw new IllegalStateException("getOutputStream() has already been called");
            }
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(capture, getCharacterEncoding()));
            }
            return writer;
        }

        public byte[] getCapturedData() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (output != null) {
                output.flush();
            }
            return capture.toByteArray();
        }
    }
}

