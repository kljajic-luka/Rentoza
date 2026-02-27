package org.example.rentoza.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OPS-GAP-7 Remediation: Centralized scheduler observability via AOP.
 *
 * <p>Intercepts every {@code @Scheduled} method invocation across all 37+ scheduled
 * jobs and emits standardized Micrometer metrics without modifying each scheduler class.
 *
 * <h2>Emitted Metrics</h2>
 * <ul>
 *   <li>{@code scheduler.execution.duration} — Timer per job (class + method tags)</li>
 *   <li>{@code scheduler.execution.success} — Counter per job on successful completion</li>
 *   <li>{@code scheduler.execution.failure} — Counter per job on exception</li>
 *   <li>{@code scheduler.execution.last_success_epoch} — Gauge: epoch seconds of last success</li>
 * </ul>
 *
 * <p>All metrics are tagged with {@code scheduler.class} (short class name) and
 * {@code scheduler.method} for per-job filtering in Grafana/Prometheus dashboards.
 *
 * <p>Example Prometheus queries:
 * <pre>
 *   # Jobs that haven't run in over 25 hours (daily jobs)
 *   time() - scheduler_execution_last_success_epoch > 90000
 *
 *   # Job failure rate
 *   rate(scheduler_execution_failure_total[5m]) > 0
 *
 *   # P99 execution duration
 *   histogram_quantile(0.99, rate(scheduler_execution_duration_seconds_bucket[5m]))
 * </pre>
 */
@Aspect
@Component
@Slf4j
public class SchedulerMetricsAspect {

    private final MeterRegistry meterRegistry;

    /**
     * Tracks last-success epoch per job for gauge registration.
     * Key: "ClassName.methodName"
     */
    private final ConcurrentHashMap<String, AtomicReference<Double>> lastSuccessEpochs =
            new ConcurrentHashMap<>();

    public SchedulerMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object instrumentScheduledMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        // Strip CGLIB proxy suffix (e.g., "BookingScheduler$$SpringCGLIB$$0" → "BookingScheduler")
        if (className.contains("$$")) {
            className = className.substring(0, className.indexOf("$$"));
        }
        String methodName = joinPoint.getSignature().getName();
        Tags tags = Tags.of("scheduler.class", className, "scheduler.method", methodName);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Object result = joinPoint.proceed();

            sample.stop(Timer.builder("scheduler.execution.duration")
                    .tags(tags)
                    .description("Scheduler job execution duration")
                    .register(meterRegistry));

            meterRegistry.counter("scheduler.execution.success", tags).increment();

            // Update last-success epoch gauge
            String jobKey = className + "." + methodName;
            lastSuccessEpochs
                    .computeIfAbsent(jobKey, k -> {
                        AtomicReference<Double> ref = new AtomicReference<>((double) Instant.now().getEpochSecond());
                        io.micrometer.core.instrument.Gauge.builder(
                                        "scheduler.execution.last_success_epoch", ref, AtomicReference::get)
                                .tags(tags)
                                .description("Epoch seconds of last successful execution")
                                .register(meterRegistry);
                        return ref;
                    })
                    .set((double) Instant.now().getEpochSecond());

            return result;
        } catch (Throwable t) {
            sample.stop(Timer.builder("scheduler.execution.duration")
                    .tags(tags)
                    .description("Scheduler job execution duration")
                    .register(meterRegistry));

            meterRegistry.counter("scheduler.execution.failure", tags).increment();

            log.error("[SchedulerMetrics] {} failed: {}", jobKey(className, methodName), t.getMessage());
            throw t;
        }
    }

    private static String jobKey(String className, String methodName) {
        return className + "." + methodName;
    }
}
