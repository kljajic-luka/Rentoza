package org.example.rentoza.booking.photo;

import org.example.rentoza.storage.SupabaseStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhotoUrlServiceStartupValidationTest {

    @Test
    @DisplayName("startup fails in prod when Redis-backed signed URL cache is disabled")
    void validateCacheTtlConsistency_failsInProdWithoutRedis() {
        PhotoUrlService service = createService(true, false, 840, 900);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateCacheTtlConsistency"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required in production");
    }

    @Test
    @DisplayName("startup allows non-prod fallback cache when Redis is disabled")
    void validateCacheTtlConsistency_allowsNonProdFallbackWithoutRedis() {
        PhotoUrlService service = createService(false, false, 840, 900);

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "validateCacheTtlConsistency"))
                .doesNotThrowAnyException();
    }

    private PhotoUrlService createService(boolean productionProfile, boolean redisEnabled,
                                          int cacheTtlSeconds, int signedUrlExpirySeconds) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(org.mockito.ArgumentMatchers.any(Profiles.class))).thenReturn(productionProfile);

        PhotoUrlService service = new PhotoUrlService(mock(SupabaseStorageService.class), environment);
        ReflectionTestUtils.setField(service, "redisEnabled", redisEnabled);
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", cacheTtlSeconds);
        ReflectionTestUtils.setField(service, "signedUrlExpirySeconds", signedUrlExpirySeconds);
        return service;
    }
}