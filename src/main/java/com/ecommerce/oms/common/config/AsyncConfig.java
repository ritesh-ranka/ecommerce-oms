package com.ecommerce.oms.common.config;

import com.ecommerce.oms.common.web.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The thread pool that drains the transactional outbox after checkout has already
 * responded.
 *
 * <p>Two properties are deliberate:
 * <ul>
 *   <li><b>Bounded</b>, with {@link ThreadPoolExecutor.CallerRunsPolicy}. Under saturation
 *       the pool degrades to synchronous execution on the caller's thread instead of
 *       silently rejecting work. Losing an order-placed event is worse than a slow
 *       response.</li>
 *   <li><b>Max pool size is well below the Hikari pool size</b> so outbox dispatch can
 *       never starve request threads of database connections.</li>
 * </ul>
 *
 * <p>The {@link TaskDecorator} copies MDC across the thread hand-off, which is the only
 * reason an asynchronous log line can still be correlated to the request that triggered it.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String OUTBOX_EXECUTOR = "outboxExecutor";

    @Bean(OUTBOX_EXECUTOR)
    public ThreadPoolTaskExecutor outboxExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("oms-outbox-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(mdcPropagating());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        log.info("Outbox executor initialised: core=4 max=8 queue=500 policy=CallerRuns");
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return outboxExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }

    /** Carries traceId / userId from the request thread onto the async thread. */
    private TaskDecorator mdcPropagating() {
        return runnable -> {
            Map<String, String> parentContext = TraceContext.snapshot();
            return () -> {
                try {
                    TraceContext.restore(parentContext);
                    runnable.run();
                } finally {
                    TraceContext.clear();
                }
            };
        };
    }
}
