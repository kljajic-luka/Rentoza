package org.example.chatservice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RateLimitConfig.
 *
 * Tests the rate limiting logic:
 * - Per-user bucket creation
 * - Token consumption
 * - Burst limiting
 * - Hourly limiting
 *
 * All user IDs are Long (BIGINT) matching production models.
 * resolveBucket() accepts String (internal), tryConsume()/getRemainingTokens() accept Long.
 */
class RateLimitConfigTest {

    private RateLimitConfig rateLimitConfig;

    private static final Long USER_A = 123L;
    private static final Long USER_B = 456L;
    private static final Long BURST_USER = 700L;
    private static final Long BURST_EXCEED_USER = 800L;

    @BeforeEach
    void setUp() {
        rateLimitConfig = new RateLimitConfig();
    }

    @Nested
    @DisplayName("Bucket Resolution")
    class BucketResolution {

        @Test
        @DisplayName("Should create bucket for new user")
        void createsBucketForNewUser() {
            var bucket = rateLimitConfig.resolveBucket(String.valueOf(USER_A));

            assertThat(bucket).isNotNull();
            assertThat(bucket.getAvailableTokens()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should return same bucket for same user")
        void returnsSameBucketForSameUser() {
            var bucket1 = rateLimitConfig.resolveBucket(String.valueOf(USER_A));
            var bucket2 = rateLimitConfig.resolveBucket(String.valueOf(USER_A));

            assertThat(bucket1).isSameAs(bucket2);
        }

        @Test
        @DisplayName("Should return different buckets for different users")
        void returnsDifferentBucketsForDifferentUsers() {
            var bucket1 = rateLimitConfig.resolveBucket(String.valueOf(USER_A));
            var bucket2 = rateLimitConfig.resolveBucket(String.valueOf(USER_B));

            assertThat(bucket1).isNotSameAs(bucket2);
        }
    }

    @Nested
    @DisplayName("Token Consumption")
    class TokenConsumption {

        @Test
        @DisplayName("Should allow first message")
        void allowsFirstMessage() {
            boolean allowed = rateLimitConfig.tryConsume(USER_A);

            assertThat(allowed).isTrue();
        }

        @Test
        @DisplayName("Should track remaining tokens")
        void tracksRemainingTokens() {
            long initialTokens = rateLimitConfig.getRemainingTokens(USER_B);

            rateLimitConfig.tryConsume(USER_B);

            long tokensAfterConsume = rateLimitConfig.getRemainingTokens(USER_B);
            assertThat(tokensAfterConsume).isLessThan(initialTokens);
        }
    }

    @Nested
    @DisplayName("Burst Limiting")
    class BurstLimiting {

        @Test
        @DisplayName("Should allow burst limit number of messages")
        void allowsBurstLimit() {
            int burstLimit = RateLimitConfig.getBurstLimit();

            // Send burst limit messages
            for (int i = 0; i < burstLimit; i++) {
                boolean allowed = rateLimitConfig.tryConsume(BURST_USER);
                assertThat(allowed)
                    .as("Message %d should be allowed", i + 1)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("Should block message exceeding burst limit")
        void blocksExceedingBurstLimit() {
            int burstLimit = RateLimitConfig.getBurstLimit();

            // Consume all burst tokens
            for (int i = 0; i < burstLimit; i++) {
                rateLimitConfig.tryConsume(BURST_EXCEED_USER);
            }

            // Next message should be blocked
            boolean allowed = rateLimitConfig.tryConsume(BURST_EXCEED_USER);
            assertThat(allowed).isFalse();
        }
    }

    @Nested
    @DisplayName("Cleanup")
    class Cleanup {

        @Test
        @DisplayName("Should clear all buckets")
        void clearsAllBuckets() {
            // Create some buckets via tryConsume
            rateLimitConfig.tryConsume(USER_A);
            rateLimitConfig.tryConsume(USER_B);

            // Clear all
            rateLimitConfig.clearAllBuckets();

            // New bucket should have full tokens (bucket recreated on access)
            long tokens = rateLimitConfig.getRemainingTokens(USER_A);
            assertThat(tokens).isEqualTo(RateLimitConfig.getBurstLimit());
        }
    }

    @Nested
    @DisplayName("Configuration Values")
    class ConfigurationValues {

        @Test
        @DisplayName("Should have correct hourly limit")
        void correctHourlyLimit() {
            assertThat(RateLimitConfig.getHourlyLimit()).isEqualTo(50);
        }

        @Test
        @DisplayName("Should have correct burst limit")
        void correctBurstLimit() {
            assertThat(RateLimitConfig.getBurstLimit()).isEqualTo(10);
        }
    }
}
