package com.couchbase.fhir.resources.interceptor;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.couchbase.fhir.resources.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class FastpathResponseInterceptor extends InterceptorAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(FastpathResponseInterceptor.class);
    
    @Override
    public boolean outgoingResponse(RequestDetails theRequestDetails) {
        // Check for fastpath UTF-8 bytes (2x memory savings vs String)
        Object fastpathData = theRequestDetails.getUserData().get(SearchService.FASTPATH_BYTES_ATTRIBUTE);
        
        if (fastpathData instanceof byte[]) {
            byte[] jsonBytes = (byte[]) fastpathData;
            logger.debug("🚀 FASTPATH INTERCEPTOR: Detected fastpath bytes ({} bytes), bypassing HAPI serialization", jsonBytes.length);
            
            try {
                if (theRequestDetails instanceof ServletRequestDetails) {
                    ServletRequestDetails servletDetails = (ServletRequestDetails) theRequestDetails;
                    HttpServletResponse response = servletDetails.getServletResponse();
                    
                    response.setContentType("application/fhir+json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_OK);
                    
                    // Write UTF-8 bytes directly - let Tomcat handle compression
                    response.getOutputStream().write(jsonBytes);
                    response.getOutputStream().flush();
                    
                    logger.debug("🚀 FASTPATH INTERCEPTOR: Wrote {} bytes to response (Tomcat will compress if enabled)", jsonBytes.length);
                    
                    return false;
                }
            } catch (IOException e) {
                logger.error("🚀 FASTPATH INTERCEPTOR: Failed to write fastpath bytes: {}", e.getMessage());
            }
        }
        
        return true;
    }
}
