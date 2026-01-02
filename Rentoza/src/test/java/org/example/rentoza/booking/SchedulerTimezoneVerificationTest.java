package org.example.rentoza.booking;

import org.example.rentoza.admin.service.AdminDashboardService;
import org.example.rentoza.auth.TokenCleanupScheduler;
import org.example.rentoza.booking.checkin.CheckInScheduler;
import org.example.rentoza.booking.checkout.CheckOutScheduler;
import org.example.rentoza.notification.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verification tests for scheduler timezone configuration.
 * 
 * Phase 1 Critical Fix: All @Scheduled methods must specify zone = "Europe/Belgrade"
 * to ensure consistent execution times across DST transitions and server relocations.
 * 
 * These tests use reflection to verify the annotations are correctly configured,
 * preventing timezone drift bugs at compile time.
 * 
 * @see Time_Window_Logic_Improvement_Plan.md - Section 1.3 Scheduler Inventory
 */
class SchedulerTimezoneVerificationTest {

    private static final String REQUIRED_TIMEZONE = "Europe/Belgrade";

    @Test
    @DisplayName("BookingScheduler must have Europe/Belgrade timezone")
    void bookingSchedulerHasCorrectTimezone() {
        verifySchedulerTimezone(BookingScheduler.class, "autoExpirePendingBookings");
    }

    @Test
    @DisplayName("CheckInScheduler must have Europe/Belgrade timezone on all methods")
    void checkInSchedulerHasCorrectTimezones() {
        verifySchedulerTimezone(CheckInScheduler.class, "openCheckInWindows");
        verifySchedulerTimezone(CheckInScheduler.class, "sendCheckInReminders");
        verifySchedulerTimezone(CheckInScheduler.class, "detectNoShows");
    }

    @Test
    @DisplayName("CheckOutScheduler must have Europe/Belgrade timezone on all methods")
    void checkOutSchedulerHasCorrectTimezones() {
        verifySchedulerTimezone(CheckOutScheduler.class, "openCheckoutWindows");
        verifySchedulerTimezone(CheckOutScheduler.class, "sendCheckoutReminders");
        verifySchedulerTimezone(CheckOutScheduler.class, "escalateOverdueReturns");
    }

    @Test
    @DisplayName("TokenCleanupScheduler must have Europe/Belgrade timezone")
    void tokenCleanupSchedulerHasCorrectTimezone() {
        verifySchedulerTimezone(TokenCleanupScheduler.class, "cleanupExpiredTokens");
        verifySchedulerTimezone(TokenCleanupScheduler.class, "frequentCleanup");
    }

    @Test
    @DisplayName("All @Scheduled methods in booking package have timezone specified")
    void allBookingSchedulersHaveTimezone() {
        List<String> missingTimezone = new ArrayList<>();
        
        // Classes to check
        Class<?>[] schedulerClasses = {
            BookingScheduler.class,
            CheckInScheduler.class,
            CheckOutScheduler.class
        };

        for (Class<?> clazz : schedulerClasses) {
            for (Method method : clazz.getDeclaredMethods()) {
                Scheduled scheduled = method.getAnnotation(Scheduled.class);
                if (scheduled != null) {
                    String zone = scheduled.zone();
                    if (zone == null || zone.isEmpty() || !zone.equals(REQUIRED_TIMEZONE)) {
                        missingTimezone.add(clazz.getSimpleName() + "." + method.getName() 
                                + " (zone=" + (zone.isEmpty() ? "<empty>" : zone) + ")");
                    }
                }
            }
        }

        assertThat(missingTimezone)
                .as("All @Scheduled methods must specify zone='Europe/Belgrade'")
                .isEmpty();
    }

    /**
     * Helper method to verify a specific scheduler method has the correct timezone.
     */
    private void verifySchedulerTimezone(Class<?> clazz, String methodName) {
        try {
            Method method = findMethod(clazz, methodName);
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled)
                    .as("Method %s.%s must have @Scheduled annotation", clazz.getSimpleName(), methodName)
                    .isNotNull();

            assertThat(scheduled.zone())
                    .as("@Scheduled on %s.%s must specify zone='%s'", 
                            clazz.getSimpleName(), methodName, REQUIRED_TIMEZONE)
                    .isEqualTo(REQUIRED_TIMEZONE);

        } catch (NoSuchMethodException e) {
            fail("Method not found: " + clazz.getSimpleName() + "." + methodName);
        }
    }

    /**
     * Find a method by name (handles overloaded methods by returning first match).
     */
    private Method findMethod(Class<?> clazz, String methodName) throws NoSuchMethodException {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new NoSuchMethodException(methodName);
    }
}
