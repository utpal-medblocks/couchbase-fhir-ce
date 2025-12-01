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
        logger.info("════════════════════════════════════════════════════════════");
        logger.info("🔧 [SMART-ENHANCER] SmartTokenEnhancerFilter INITIALIZED (Order: HIGHEST_PRECEDENCE)");
        logger.info("════════════════════════════════════════════════════════════");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();
        
        logger.info("🔍 [SMART-ENHANCER] Request: {} {}", method, uri);

        // Only intercept POST requests to /oauth2/token
        if ("POST".equalsIgnoreCase(method) && uri.endsWith("/oauth2/token")) {
            logger.info("════════════════════════════════════════════════════════════");
            logger.info("🎫 [SMART-ENHANCER] Intercepting token endpoint: {} {}", method, uri);
            logger.info("════════════════════════════════════════════════════════════");
            
            // Wrap response to capture output
            ResponseCaptureWrapper responseWrapper = new ResponseCaptureWrapper(httpResponse);
            
            // Continue filter chain
            chain.doFilter(request, responseWrapper);
            
            // Get the captured response
            byte[] responseData = responseWrapper.getCapturedData();
            String responseBody = new String(responseData, StandardCharsets.UTF_8);
            
            logger.info("📦 [SMART-ENHANCER] Original response length: {} bytes", responseData.length);
            logger.info("📦 [SMART-ENHANCER] Original response: {}", responseBody.substring(0, Math.min(200, responseBody.length())));
            
            try {
                // Parse JSON response
                @SuppressWarnings("unchecked")
                Map<String, Object> tokenResponse = objectMapper.readValue(responseBody, Map.class);
                
                logger.info("📋 [SMART-ENHANCER] Original keys: {}", tokenResponse.keySet());
                
                // Check if patient claim already exists
                if (tokenResponse.containsKey("patient")) {
                    logger.info("✅ [SMART-ENHANCER] Patient claim already present: {}", tokenResponse.get("patient"));
                } else {
                    // Extract from access_token JWT
                    String accessToken = (String) tokenResponse.get("access_token");
                    if (accessToken != null && accessToken.contains(".")) {
                        String[] parts = accessToken.split("\\.");
                        if (parts.length >= 2) {
                            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                            @SuppressWarnings("unchecked")
                            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
                            
                            logger.info("🔐 [SMART-ENHANCER] JWT claims: {}", claims.keySet());
                            
                            Object patientClaim = claims.get("patient");
                            if (patientClaim != null) {
                                // Add patient to top-level response
                                tokenResponse.put("patient", patientClaim);
                                logger.info("✅ [SMART-ENHANCER] Injected patient claim: {}", patientClaim);
                                
                                // Rewrite response
                                String modifiedResponse = objectMapper.writeValueAsString(tokenResponse);
                                byte[] modifiedData = modifiedResponse.getBytes(StandardCharsets.UTF_8);
                                
                                logger.info("📦 [SMART-ENHANCER] Modified response length: {} bytes", modifiedData.length);
                                logger.info("📦 [SMART-ENHANCER] Modified keys: {}", tokenResponse.keySet());
                                logger.info("════════════════════════════════════════════════════════════");
                                
                                // Write modified response
                                httpResponse.setContentLength(modifiedData.length);
                                httpResponse.getOutputStream().write(modifiedData);
                                return;
                            } else {
                                logger.warn("⚠️ [SMART-ENHANCER] No patient claim in JWT. Claims: {}", claims.keySet());
                            }
                        }
                    }
                }
                
                logger.info("════════════════════════════════════════════════════════════");
                
            } catch (Exception e) {
                logger.error("❌ [SMART-ENHANCER] Error processing token response: {}", e.getMessage(), e);
            }
            
            // Write original response if no modification was made
            httpResponse.getOutputStream().write(responseData);
            
        } else {
            // Not a token endpoint request, pass through
            chain.doFilter(request, response);
        }
    }

    /**
     * Response wrapper that captures the response data
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
                        capture.write(b);
                        ResponseCaptureWrapper.super.getOutputStream().write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        capture.write(b, off, len);
                        ResponseCaptureWrapper.super.getOutputStream().write(b, off, len);
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

