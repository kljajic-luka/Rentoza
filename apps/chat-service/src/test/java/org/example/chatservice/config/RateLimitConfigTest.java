//package org.example.chatservice.config;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
///**
// * Unit tests for RateLimitConfig.
// *
// * Tests the rate limiting logic:
// * - Per-user bucket creation
// * - Token consumption
// * - Burst limiting
// * - Hourly limiting
// */
//class RateLimitConfigTest {
//
//    private RateLimitConfig rateLimitConfig;
//
//    @BeforeEach
//    void setUp() {
//        rateLimitConfig = new RateLimitConfig();
//    }
//
//    @Nested
//    @DisplayName("Bucket Resolution")
//    class BucketResolution {
//
//        @Test
//        @DisplayName("Should create bucket for new user")
//        void createsBucketForNewUser() {
//            var bucket = rateLimitConfig.resolveBucket("user-123");
//
//            assertThat(bucket).isNotNull();
//            assertThat(bucket.getAvailableTokens()).isGreaterThan(0);
//        }
//
//        @Test
//        @DisplayName("Should return same bucket for same user")
//        void returnsSameBucketForSameUser() {
//            var bucket1 = rateLimitConfig.resolveBucket("user-123");
//            var bucket2 = rateLimitConfig.resolveBucket("user-123");
//
//            assertThat(bucket1).isSameAs(bucket2);
//        }
//
//        @Test
//        @DisplayName("Should return different buckets for different users")
//        void returnsDifferentBucketsForDifferentUsers() {
//            var bucket1 = rateLimitConfig.resolveBucket("user-123");
//            var bucket2 = rateLimitConfig.resolveBucket("user-456");
//
//            assertThat(bucket1).isNotSameAs(bucket2);
//        }
//    }
//
//    @Nested
//    @DisplayName("Token Consumption")
//    class TokenConsumption {
//
//        @Test
//        @DisplayName("Should allow first message")
//        void allowsFirstMessage() {
//            boolean allowed = rateLimitConfig.tryConsume(Long.parseLong("user-123"));
//
//            assertThat(allowed).isTrue();
//        }
//
//        @Test
//        @DisplayName("Should track remaining tokens")
//        void tracksRemainingTokens() {
//            String userId = "user-456";
//            long initialTokens = rateLimitConfig.getRemainingTokens(userId);
//
//            rateLimitConfig.tryConsume(userId);
//
//            long tokensAfterConsume = rateLimitConfig.getRemainingTokens(userId);
//            assertThat(tokensAfterConsume).isLessThan(initialTokens);
//        }
//    }
//
//    @Nested
//    @DisplayName("Burst Limiting")
//    class BurstLimiting {
//
//        @Test
//        @DisplayName("Should allow burst limit number of messages")
//        void allowsBurstLimit() {
//            String userId = "burst-test-user";
//            int burstLimit = RateLimitConfig.getBurstLimit();
//
//            // Send burst limit messages
//            for (int i = 0; i < burstLimit; i++) {
//                boolean allowed = rateLimitConfig.tryConsume(userId);
//                assertThat(allowed)
//                    .as("Message %d should be allowed", i + 1)
//                    .isTrue();
//            }
//        }
//
//        @Test
//        @DisplayName("Should block message exceeding burst limit")
//        void blocksExceedingBurstLimit() {
//            String userId = "burst-exceed-user";
//            int burstLimit = RateLimitConfig.getBurstLimit();
//
//            // Consume all burst tokens
//            for (int i = 0; i < burstLimit; i++) {
//                rateLimitConfig.tryConsume(userId);
//            }
//
//            // Next message should be blocked
//            boolean allowed = rateLimitConfig.tryConsume(userId);
//            assertThat(allowed).isFalse();
//        }
//    }
//
//    @Nested
//    @DisplayName("Cleanup")
//    class Cleanup {
//
//        @Test
//        @DisplayName("Should clear all buckets")
//        void clearsAllBuckets() {
//            // Create some buckets
//            rateLimitConfig.resolveBucket("user-1");
//            rateLimitConfig.resolveBucket("user-2");
//
//            // Clear all
//            rateLimitConfig.clearAllBuckets();
//
//            // New bucket should have full tokens
//            long tokens = rateLimitConfig.getRemainingTokens(Long.parseLong("user-1"));
//            // New bucket is created on access, should have initial tokens
//            assertThat(tokens).isEqualTo(RateLimitConfig.getBurstLimit());
//        }
//    }
//
//    @Nested
//    @DisplayName("Configuration Values")
//    class ConfigurationValues {
//
//        @Test
//        @DisplayName("Should have correct hourly limit")
//        void correctHourlyLimit() {
//            assertThat(RateLimitConfig.getHourlyLimit()).isEqualTo(50);
//        }
//
//        @Test
//        @DisplayName("Should have correct burst limit")
//        void correctBurstLimit() {
//            assertThat(RateLimitConfig.getBurstLimit()).isEqualTo(5);
//        }
//    }
//}
