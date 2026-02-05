package org.example.rentoza.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Async executor configuration for Phase 2 architecture improvements.
 * 
 * <h2>Executor Pools</h2>
 * <ul>
 *   <li>{@code viewSyncExecutor} - CQRS read model synchronization</li>
 *   <li>{@code photoProcessingExecutor} - Async photo EXIF validation</li>
 *   <li>{@code notificationExecutor} - Async notifications</li>
 *   <li>{@code sagaExecutor} - Checkout saga orchestration</li>
 * </ul>
 * 
 * <h2>Thread Pool Sizing</h2>
 * <p>Pools are sized based on task characteristics:
 * <ul>
 *   <li>I/O-bound tasks: More threads (2x CPU cores)</li>
 *   <li>CPU-bound tasks: Fewer threads (1x CPU cores)</li>
 * </ul>
 * 
 * @see org.example.rentoza.booking.checkin.cqrs.CheckInStatusViewSyncListener
 */
@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * View sync executor for CQRS read model updates.
     * 
     * <p>I/O-bound (database writes), so we use 2x CPU cores.
     * Queue capacity is high to handle bursts during peak check-in times.
     */
    @Bean(name = "viewSyncExecutor")
    public Executor viewSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, CPU_COUNT));
        executor.setMaxPoolSize(CPU_COUNT * 2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("view-sync-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("[Async] View sync task rejected - queue full. Task: {}", r.getClass().getSimpleName());
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("[Async] ViewSyncExecutor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 500);
        
        return executor;
    }

    /**
     * Photo processing executor for EXIF validation.
     * 
     * <p>Mixed I/O and CPU-bound (file reads + image processing).
     * Moderate pool size with higher queue for async processing.
     */
    @Bean(name = "photoProcessingExecutor")
    public Executor photoProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, CPU_COUNT / 2));
        executor.setMaxPoolSize(CPU_COUNT);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("photo-proc-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("[Async] Photo processing task rejected - queue full");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);  // Allow more time for image processing
        executor.initialize();
        
        log.info("[Async] PhotoProcessingExecutor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 200);
        
        return executor;
    }

    /**
     * Notification executor for async notification delivery.
     * 
     * <p>I/O-bound (external service calls), so we use higher thread count.
     */
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, CPU_COUNT));
        executor.setMaxPoolSize(CPU_COUNT * 2);
        executor.setQueueCapacity(1000);  // High capacity for notification bursts
        executor.setThreadNamePrefix("notification-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("[Async] Notification task rejected - queue full");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("[Async] NotificationExecutor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 1000);
        
        return executor;
    }

    @Bean(name = "sagaExecutor")
    public Executor sagaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, CPU_COUNT / 2));
        executor.setMaxPoolSize(CPU_COUNT);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("saga-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.error("[Async] Saga task rejected - queue full! This may indicate saga processing issues.");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);  // Allow sagas to complete on shutdown
        executor.initialize();
        
        log.info("[Async] SagaExecutor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 100);
        
        return executor;
    }

    /**
     * Renter verification executor for OCR and risk processing.
     * 
     * <p>Isolated pool to prevent document processing from starving other services.
     */
    @Bean(name = "renterVerificationExecutor")
    public Executor renterVerificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); // Start small, dedicated
        executor.setMaxPoolSize(10); // Cap burst
        executor.setQueueCapacity(50); // Queue before rejection
        executor.setThreadNamePrefix("renter-verif-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("[Async] Renter verification task rejected - queue full");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        
        log.info("[Async] RenterVerificationExecutor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 50);
        
        return executor;
    }

    /**
     * Default executor for general async tasks.
     */
    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, CPU_COUNT / 2));
        executor.setMaxPoolSize(CPU_COUNT);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("[Async] Default TaskExecutor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 500);
        
        return executor;
    }

    /**
     * Global exception handler for async tasks.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncExceptionHandler();
    }

    /**
     * Custom handler that logs uncaught exceptions from async tasks.
     */
    private static class AsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("[Async] Uncaught exception in async method {}.{}: {}",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    ex.getMessage(),
                    ex);
        }
    }
}
