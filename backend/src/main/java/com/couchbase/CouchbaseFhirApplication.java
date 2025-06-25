package com.couchbase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.couchbase.CouchbaseDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.couchbase.CouchbaseReactiveDataAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = {CouchbaseDataAutoConfiguration.class, CouchbaseReactiveDataAutoConfiguration.class})
@EnableConfigurationProperties
public class CouchbaseFhirApplication {

	public static void main(String[] args) {
		SpringApplication.run(CouchbaseFhirApplication.class, args);
	}

}
