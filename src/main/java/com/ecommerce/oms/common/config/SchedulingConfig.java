package com.ecommerce.oms.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Two background jobs run on this scheduler:
 * <ul>
 *   <li>{@code ReservationSweeper} — releases expired stock reservations. This is the
 *       safety net that makes every mid-flight crash in checkout recoverable.</li>
 *   <li>{@code OutboxRetryScheduler} — redelivers stale outbox events and promotes
 *       repeat failures to DEAD_LETTER.</li>
 * </ul>
 * A dedicated two-thread scheduler keeps them off the request executor, so a slow sweep
 * cannot delay a dispatch or vice versa.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("oms-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
