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
        Object fastpathJson = theRequestDetails.getUserData().get(SearchService.FASTPATH_JSON_ATTRIBUTE);
        
        if (fastpathJson instanceof String) {
            String json = (String) fastpathJson;
            logger.info("🚀 FASTPATH INTERCEPTOR: Detected fastpath JSON ({} bytes), bypassing HAPI serialization", json.length());
            
            try {
                if (theRequestDetails instanceof ServletRequestDetails) {
                    ServletRequestDetails servletDetails = (ServletRequestDetails) theRequestDetails;
                    HttpServletResponse response = servletDetails.getServletResponse();
                    
                    response.setContentType("application/fhir+json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_OK);
                    
                    // Write plain JSON - let Tomcat handle compression
                    response.getWriter().write(json);
                    response.getWriter().flush();
                    
                    logger.debug("🚀 FASTPATH INTERCEPTOR: Wrote {} bytes to response (Tomcat will compress if enabled)", json.length());
                    
                    return false;
                }
            } catch (IOException e) {
                logger.error("🚀 FASTPATH INTERCEPTOR: Failed to write fastpath JSON: {}", e.getMessage());
            }
        }
        
        return true;
    }
}
