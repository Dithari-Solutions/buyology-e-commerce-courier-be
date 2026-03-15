package com.buyology.buyology_courier.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class AsyncConfig {

    @Bean(name = "eventPublisherExecutor")
    Executor eventPublisherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("event-pub-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 30 seconds gives in-flight events time to drain to the broker on shutdown.
        // The previous value of 10s dropped tasks silently if the queue had many pending
        // items and the broker was slow. 30s aligns with server.shutdown graceful timeout.
        executor.setAwaitTerminationSeconds(30);

        // When the queue fills up (broker down for extended period), the default
        // AbortPolicy throws TaskRejectedException into a background thread that nobody
        // catches — events vanish silently. This handler logs a critical alert instead.
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("CRITICAL: event-pub executor queue full — task rejected and event will be lost. " +
                        "Check broker connectivity. Task: {}", task.getClass().getSimpleName())
        );

        executor.initialize();
        return executor;
    }
}
