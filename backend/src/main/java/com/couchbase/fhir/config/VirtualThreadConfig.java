package com.couchbase.fhir.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

/**
 * Enables Virtual Threads for Tomcat request processing and Spring Task execution
 * when running on Java 21+. This ensures each incoming request runs on a
 * lightweight virtual thread and all async tasks use virtual threads as well.
 */
@Configuration
public class VirtualThreadConfig {

    /**
     * Customizes Tomcat's protocol handler to use a per-task virtual thread executor.
     * This makes request handling use virtual threads instead of a limited platform thread pool.
     */
    @Bean
    TomcatProtocolHandlerCustomizer<Http11NioProtocol> protocolHandlerVirtualThreads() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return protocolHandler -> protocolHandler.setExecutor(executor);
    }

    /**
     * Exposes a Spring {@link TaskExecutor} backed by virtual threads for general async tasks.
     * Spring Boot will prefer this for @Async and internal task scheduling when present.
     */
    @Bean
    TaskExecutor virtualThreadTaskExecutor() {
        return new ConcurrentTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}
