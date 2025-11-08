package org.example.rentoza.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration for async task execution and scheduled tasks.
 *
 * @EnableAsync - Enables asynchronous method execution (@Async)
 * @EnableScheduling - Enables scheduled task execution (@Scheduled)
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
    // Uses Spring Boot's default async executor configuration
    // Can be customized if needed by defining a TaskExecutor bean
}
