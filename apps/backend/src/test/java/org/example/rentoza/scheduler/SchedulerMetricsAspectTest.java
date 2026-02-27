package org.example.rentoza.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tests for OPS-GAP-7 remediation: Centralized scheduler observability via AOP.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Success counter increments after successful execution</li>
 *   <li>Failure counter increments after exception</li>
 *   <li>Duration timer records for both success and failure</li>
 *   <li>Last-success epoch gauge is set on success</li>
 *   <li>CGLIB proxy suffixes are stripped from class names in tags</li>
 *   <li>Exceptions are re-thrown after metric recording</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OPS-GAP-7: Scheduler Metrics Aspect")
class SchedulerMetricsAspectTest {

    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private Signature signature;

    private MeterRegistry meterRegistry;
    private SchedulerMetricsAspect aspect;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        aspect = new SchedulerMetricsAspect(meterRegistry);
    }

    private void stubJoinPoint(Object target, String methodName) throws Throwable {
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn(methodName);
    }

    @Nested
    @DisplayName("Success Path")
    class SuccessPath {

        @Test
        @DisplayName("Increments success counter on successful execution")
        void incrementsSuccessCounter() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenReturn(null);

            aspect.instrumentScheduledMethod(joinPoint);

            assertThat(meterRegistry.counter(
                    "scheduler.execution.success",
                    "scheduler.class", "FakeScheduler",
                    "scheduler.method", "runJob").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Records duration timer on success")
        void recordsDurationTimer() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenReturn(null);

            aspect.instrumentScheduledMethod(joinPoint);

            Timer timer = meterRegistry.find("scheduler.execution.duration")
                    .tag("scheduler.class", "FakeScheduler")
                    .tag("scheduler.method", "runJob")
                    .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Sets last-success epoch gauge on success")
        void setsLastSuccessEpoch() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenReturn(null);

            long beforeEpoch = java.time.Instant.now().getEpochSecond();
            aspect.instrumentScheduledMethod(joinPoint);
            long afterEpoch = java.time.Instant.now().getEpochSecond();

            double gaugeValue = meterRegistry.get("scheduler.execution.last_success_epoch")
                    .tag("scheduler.class", "FakeScheduler")
                    .tag("scheduler.method", "runJob")
                    .gauge().value();

            assertThat(gaugeValue).isBetween((double) beforeEpoch, (double) afterEpoch);
        }

        @Test
        @DisplayName("Multiple invocations accumulate success counter")
        void multipleInvocationsAccumulate() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenReturn(null);

            aspect.instrumentScheduledMethod(joinPoint);
            aspect.instrumentScheduledMethod(joinPoint);
            aspect.instrumentScheduledMethod(joinPoint);

            assertThat(meterRegistry.counter(
                    "scheduler.execution.success",
                    "scheduler.class", "FakeScheduler",
                    "scheduler.method", "runJob").count())
                    .isEqualTo(3.0);
        }
    }

    @Nested
    @DisplayName("Failure Path")
    class FailurePath {

        @Test
        @DisplayName("Increments failure counter on exception")
        void incrementsFailureCounter() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenThrow(new RuntimeException("DB error"));

            assertThatThrownBy(() -> aspect.instrumentScheduledMethod(joinPoint))
                    .isInstanceOf(RuntimeException.class);

            assertThat(meterRegistry.counter(
                    "scheduler.execution.failure",
                    "scheduler.class", "FakeScheduler",
                    "scheduler.method", "runJob").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Records duration timer on failure")
        void recordsDurationOnFailure() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenThrow(new RuntimeException("timeout"));

            assertThatThrownBy(() -> aspect.instrumentScheduledMethod(joinPoint))
                    .isInstanceOf(RuntimeException.class);

            Timer timer = meterRegistry.find("scheduler.execution.duration")
                    .tag("scheduler.class", "FakeScheduler")
                    .tag("scheduler.method", "runJob")
                    .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Re-throws original exception after recording metrics")
        void rethrowsException() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenThrow(new IllegalStateException("bad state"));

            assertThatThrownBy(() -> aspect.instrumentScheduledMethod(joinPoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("bad state");
        }

        @Test
        @DisplayName("Does not increment success counter on failure")
        void noSuccessOnFailure() throws Throwable {
            stubJoinPoint(new FakeScheduler(), "runJob");
            when(joinPoint.proceed()).thenThrow(new RuntimeException("fail"));

            assertThatThrownBy(() -> aspect.instrumentScheduledMethod(joinPoint))
                    .isInstanceOf(RuntimeException.class);

            assertThat(meterRegistry.counter(
                    "scheduler.execution.success",
                    "scheduler.class", "FakeScheduler",
                    "scheduler.method", "runJob").count())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("CGLIB Proxy Handling")
    class CglibProxyHandling {

        @Test
        @DisplayName("Strips CGLIB proxy suffix from class name in tags")
        void stripsCglibSuffix() throws Throwable {
            // Simulate a CGLIB-proxied class name by using an inner class with $$ in its name
            Object proxyTarget = new FakeScheduler$$SpringCGLIB$$0();
            stubJoinPoint(proxyTarget, "execute");
            when(joinPoint.proceed()).thenReturn(null);

            aspect.instrumentScheduledMethod(joinPoint);

            assertThat(meterRegistry.counter(
                    "scheduler.execution.success",
                    "scheduler.class", "FakeScheduler",
                    "scheduler.method", "execute").count())
                    .isEqualTo(1.0);
        }
    }

    // Test helper classes
    static class FakeScheduler {
        // Simulates a real scheduler class
    }

    // Simulates a CGLIB-proxied class name pattern
    @SuppressWarnings("all")
    static class FakeScheduler$$SpringCGLIB$$0 extends FakeScheduler {
    }
}
