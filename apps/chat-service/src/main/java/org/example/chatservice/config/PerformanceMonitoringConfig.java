package org.example.chatservice.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Performance Monitoring Configuration
 * 
 * <h3>Metrics Enabled:</h3>
 * <ul>
 *   <li><strong>auth.jwt.validation</strong>: JWT validation duration (p50, p95, p99)</li>
 *   <li><strong>auth.user.mapping</strong>: UUID to BIGINT mapping duration</li>
 *   <li><strong>rls.conversation.select</strong>: RLS query performance</li>
 *   <li><strong>cache.userIdMapping.*</strong>: Cache hit rate, evictions, size</li>
 * </ul>
 * 
 * <h3>Annotations Used:</h3>
 * <ul>
 *   <li>@Timed: Method-level timing with percentiles</li>
 *   <li>Micrometer AOP: Automatic metric collection</li>
 * </ul>
 * 
 * <h3>Metrics Endpoints:</h3>
 * <ul>
 *   <li>/actuator/metrics: All metrics</li>
 *   <li>/actuator/metrics/auth.jwt.validation: JWT validation metrics</li>
 *   <li>/actuator/prometheus: Prometheus scrape endpoint</li>
 * </ul>
 * 
 * <h3>SLOs Monitored:</h3>
 * <ul>
 *   <li>API response time (p95): &lt;500ms</li>
 *   <li>RLS overhead: &lt;50ms</li>
 *   <li>Cache hit rate: &gt;80%</li>
 *   <li>Auth validation failure rate: &lt;1%</li>
 * </ul>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@Configuration
@Slf4j
public class PerformanceMonitoringConfig {

    /**
     * Enable @Timed aspect for method-level performance monitoring
     * 
     * <p>Allows @Timed annotations on methods to automatically record:</p>
     * <ul>
     *   <li>Execution count</li>
     *   <li>Total execution time</li>
     *   <li>Percentiles (p50, p95, p99)</li>
     *   <li>Max execution time</li>
     * </ul>
     * 
     * @param registry Meter registry
     * @return TimedAspect for AOP-based timing
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        log.info("Performance monitoring enabled: @Timed aspect registered");
        return new TimedAspect(registry);
    }
}
