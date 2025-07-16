package com.couchbase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.couchbase.CouchbaseDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.couchbase.CouchbaseReactiveDataAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication(exclude = {CouchbaseDataAutoConfiguration.class, CouchbaseReactiveDataAutoConfiguration.class})
@EnableConfigurationProperties
@ComponentScan(basePackages = {
    "com.couchbase",                // Include root package (for main app)
    "com.couchbase.common.config",  // Add scanning for common config
    "com.couchbase.fhir",
    "com.couchbase.admin"
})
public class CouchbaseFhirApplication {

	public static void main(String[] args) {
		SpringApplication.run(CouchbaseFhirApplication.class, args);
	}

}
