package org.example.rentoza.scalability;

import org.example.rentoza.booking.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G9: Verifies Redis caching strategy for hot read paths.
 *
 * <h2>Caching Strategy Summary</h2>
 * <ul>
 *   <li>{@code getPublicBookedSlots}: Cached in "bookingAvailability" (60s TTL).
 *       Public calendar widget, no PII, no user-specific logic. Safe to cache.</li>
 *   <li>{@code createBooking}: Evicts "bookingAvailability" for the car. Immediate
 *       invalidation ensures calendar is accurate after new booking.</li>
 * </ul>
 *
 * <h2>Why Other Methods Are NOT Cached</h2>
 * <ul>
 *   <li>{@code getCarById}: User-specific logic (ownership check, active booking
 *       proximity). Caching would leak data across users (security vulnerability).</li>
 *   <li>{@code getBookingById}: RLS enforcement via {@code findByIdForUser}. Caching
 *       would bypass access control.</li>
 *   <li>{@code getUserById/getUserByEmail}: Returns JPA entity — caching entities
 *       causes LazyInitializationException and detached entity issues.</li>
 * </ul>
 */
class RedisCachingHotPathTest {

    @Test
    @DisplayName("G9: getPublicBookedSlots is cached in bookingAvailability")
    void getPublicBookedSlots_isCached() throws Exception {
        Method method = BookingService.class.getDeclaredMethod("getPublicBookedSlots", Long.class);
        Cacheable cacheable = method.getAnnotation(Cacheable.class);

        assertThat(cacheable)
                .as("getPublicBookedSlots must have @Cacheable")
                .isNotNull();

        assertThat(cacheable.value())
                .as("Must use bookingAvailability cache")
                .contains("bookingAvailability");

        assertThat(cacheable.key())
                .as("Cache key must include carId")
                .contains("#carId");
    }

    @Test
    @DisplayName("G9: createBooking evicts bookingAvailability cache")
    void createBooking_evictsCacheOnCreation() throws Exception {
        Method method = findMethodByName(BookingService.class, "createBooking");

        CacheEvict cacheEvict = method.getAnnotation(CacheEvict.class);

        assertThat(cacheEvict)
                .as("createBooking must have @CacheEvict")
                .isNotNull();

        assertThat(cacheEvict.value())
                .as("Must evict bookingAvailability cache")
                .contains("bookingAvailability");
    }

    @Test
    @DisplayName("G9: getBookingById is NOT cached (RLS enforcement prevents safe caching)")
    void getBookingById_isNotCached() throws Exception {
        Method method = BookingService.class.getDeclaredMethod("getBookingById", Long.class);

        assertThat(method.getAnnotation(Cacheable.class))
                .as("getBookingById MUST NOT be cached (RLS enforcement)")
                .isNull();
    }

    @Test
    @DisplayName("G9: RedisCacheConfig defines bookingAvailability cache")
    void redisCacheConfig_definesBookingAvailabilityCache() throws Exception {
        String sourceCode = readClassSource(
                Class.forName("org.example.rentoza.config.RedisCacheConfig"));

        assertThat(sourceCode)
                .as("RedisCacheConfig must define bookingAvailability cache")
                .contains("bookingAvailability");
    }

    private Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("Method " + name + " not found in " + clazz.getSimpleName());
    }

    private String readClassSource(Class<?> clazz) throws Exception {
        String relativePath = "src/main/java/"
                + clazz.getName().replace('.', '/') + ".java";
        java.nio.file.Path sourcePath = java.nio.file.Path.of(
                System.getProperty("user.dir"), relativePath);
        if (!java.nio.file.Files.exists(sourcePath)) {
            sourcePath = java.nio.file.Path.of(relativePath);
        }
        assertThat(sourcePath).as("Source file for %s must exist", clazz.getSimpleName()).exists();
        return java.nio.file.Files.readString(sourcePath);
    }
}
