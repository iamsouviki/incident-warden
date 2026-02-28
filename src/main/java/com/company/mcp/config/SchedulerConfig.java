package com.company.mcp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler configuration for job polling and processing intervals.
 * Enables scheduled task execution for incident polling and batch processing.
 */
@Configuration
@EnableAsync
public class SchedulerConfig {

    /**
     * Task scheduler for periodic incident polling.
     * Used by scheduler to poll external systems (ServiceNow, Prometheus, etc.) at regular intervals.
     */
    @Bean(name = "pollingScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler pollingScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("polling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Task scheduler for processing queued incidents.
     * Used to process batches of PENDING incidents through the agent pipeline.
     */
    @Bean(name = "processingScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler processingScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("processing-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Task scheduler for periodic cleanup and maintenance tasks.
     * Handles stale record cleanup, expired HITL request cleanup, etc.
     */
    @Bean(name = "maintenanceScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler maintenanceScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.initialize();
        return scheduler;
    }
}
