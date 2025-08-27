package com.couchbase.common.logger;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;

@Configuration
@EnableScheduling
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
