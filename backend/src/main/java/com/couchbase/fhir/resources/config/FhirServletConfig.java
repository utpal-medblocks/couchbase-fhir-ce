package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class that sets up the FHIR RESTful server servlet.
 *
 */

@Configuration
public class FhirServletConfig {

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4(); // or forR5() depending on version
    }

    @Bean
    public ServletRegistrationBean<RestfulServer> fhirServletRegistration(RestfulServer restfulServer) {
        ServletRegistrationBean<RestfulServer> registration = new ServletRegistrationBean<>(restfulServer, "/fhir/*");
        registration.setName("fhirServlet");
        return registration;
    }
}
