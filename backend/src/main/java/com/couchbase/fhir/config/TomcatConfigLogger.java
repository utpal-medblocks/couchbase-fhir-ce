package com.couchbase.fhir.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs Tomcat thread pool configuration at startup to verify settings are applied
 */
@Component
public class TomcatConfigLogger {

    private static final Logger logger = LoggerFactory.getLogger(TomcatConfigLogger.class);

    @Value("${server.tomcat.threads.max:200}")
    private int maxThreads;

    @Value("${server.tomcat.threads.min-spare:10}")
    private int minSpareThreads;

    @Value("${server.tomcat.accept-count:100}")
    private int acceptCount;

    @Value("${server.tomcat.max-connections:10000}")
    private int maxConnections;

    @Value("${server.tomcat.connection-timeout:20000}")
    private String connectionTimeout;

    @Value("${server.tomcat.max-keep-alive-requests:100}")
    private int maxKeepAliveRequests;
    
    @Value("${spring.threads.virtual.enabled:false}")
    private boolean virtualThreadsEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void logTomcatConfiguration() {
        logger.info("╔════════════════════════════════════════════════════════════╗");
        logger.info("║           TOMCAT THREAD POOL CONFIGURATION                 ║");
        logger.info("╚════════════════════════════════════════════════════════════╝");
        
        // Virtual threads status (most important!)
        if (virtualThreadsEnabled) {
            logger.info("🚀 Virtual Threads:         ENABLED (Java 21+)");
            logger.info("   ✅ Thread pool limits no longer apply");
            logger.info("   ✅ Can handle 1000+ concurrent connections efficiently");
        } else {
            logger.info("⚠️  Virtual Threads:         DISABLED");
            logger.info("   ℹ️  Using platform threads (limited by max threads below)");
        }
        logger.info("────────────────────────────────────────────────────────────");
        logger.info("📊 Max Threads:             {}", maxThreads);
        logger.info("📊 Min Spare Threads:       {}", minSpareThreads);
        logger.info("📊 Accept Count (Queue):    {}", acceptCount);
        logger.info("📊 Max Connections:         {}", maxConnections);
        logger.info("📊 Connection Timeout:      {}", connectionTimeout);
        logger.info("📊 Max Keep-Alive Requests: {}", maxKeepAliveRequests);
        logger.info("════════════════════════════════════════════════════════════");
        
        // Warn if using defaults in production without virtual threads
        if (!virtualThreadsEnabled && maxThreads == 200 && "prod".equals(System.getProperty("spring.profiles.active"))) {
            logger.warn("⚠️  Using default Tomcat thread pool (200) without virtual threads - consider enabling virtual threads for high concurrency");
        }
    }
}

