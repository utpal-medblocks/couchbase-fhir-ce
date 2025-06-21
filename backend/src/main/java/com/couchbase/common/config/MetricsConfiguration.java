package com.couchbase.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

@Configuration
@EnableCaching
public class MetricsConfiguration {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("dashboardMetrics");
    }

    @Bean
    public JvmMemoryMetrics jvmMemoryMetrics() {
        return new JvmMemoryMetrics();
    }

    @Bean
    public JvmGcMetrics jvmGcMetrics() {
        return new JvmGcMetrics();
    }

    @Bean
    public ProcessorMetrics processorMetrics() {
        return new ProcessorMetrics();
    }

    @Bean
    public UptimeMetrics uptimeMetrics() {
        return new UptimeMetrics();
    }

    // Register custom metrics on startup
    @Bean
    public MetricsInitializer metricsInitializer(MeterRegistry meterRegistry) {
        return new MetricsInitializer(meterRegistry);
    }

    public static class MetricsInitializer {
        private final MeterRegistry meterRegistry;

        public MetricsInitializer(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            initializeCustomMetrics();
        }

        private void initializeCustomMetrics() {
            // Initialize custom gauges and counters
            meterRegistry.gauge("application.startup.time", System.currentTimeMillis());
        }
    }
}
