package com.couchbase.common.logger;

// import org.springframework.context.annotation.Configuration; // Disabled for Beta release
// import org.springframework.scheduling.annotation.EnableScheduling; // Disabled for Beta release
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;

// @Configuration - Disabled for Beta release
// @EnableScheduling - Disabled for Beta release
public class SchedulingConfig implements SchedulingConfigurer {

    private final LogUploadService logUploadService;

    public SchedulingConfig(LogUploadService logUploadService) {
        this.logUploadService = logUploadService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        PeriodicTrigger trigger = new PeriodicTrigger(Duration.ofMillis(logUploadService.getIntervalMs()));
        trigger.setFixedRate(false); // false = fixedDelay, true = fixedRate

        taskRegistrar.addTriggerTask(
            logUploadService::uploadLogs,
            trigger
        );
    }
}
