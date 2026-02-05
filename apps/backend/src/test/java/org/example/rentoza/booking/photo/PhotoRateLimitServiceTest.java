//package org.example.rentoza.booking.photo;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * P0-3 Security Test Suite: Rate Limiting
// *
// * Verifies that rate limiting prevents mass scraping attacks.
// * Ensures users cannot download unlimited photos in short timeframe.
// */
//@ExtendWith(MockitoExtension.class)
//public class PhotoRateLimitServiceTest {
//
//    private PhotoRateLimitService rateLimitService;
//
//    private static final Long USER_ID = 100L;
//    private static final String IP_ADDRESS = "192.168.1.1";
//    private static final int MAX_REQUESTS = 100;
//    private static final int WINDOW_MINUTES = 10;
//
//    @BeforeEach
//    void setUp() {
//        // Create service with test limits: 100 requests per 10 minutes
//        rateLimitService = new PhotoRateLimitService(MAX_REQUESTS, WINDOW_MINUTES);
//    }
//
//    /**
//     * P0-3 Test 1: Allow requests under limit
//     */
//    @Test
//    void shouldAllowRequestsUnderLimit() {
//        // When - Make 50 requests
//        for (int i = 0; i < 50; i++) {
//            boolean allowed = rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//
//            // Then
//            assertTrue(allowed, "Request " + (i + 1) + " should be allowed (under limit)");
//        }
//
//        // Verify counter is accurate
//        int count = rateLimitService.getCurrentRequestCount(USER_ID);
//        assertEquals(50, count, "Should have exactly 50 requests recorded");
//    }
//
//    /**
//     * P0-3 Test 2: Block requests over limit (CRITICAL SECURITY TEST)
//     */
//    @Test
//    void shouldBlockRequestsOverLimit() {
//        // Given - Exhaust the rate limit
//        for (int i = 0; i < MAX_REQUESTS; i++) {
//            boolean allowed = rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//            assertTrue(allowed, "First " + MAX_REQUESTS + " requests should be allowed");
//        }
//
//        // When - Try request over limit
//        boolean allowed101 = rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//
//        // Then - Should be blocked
//        assertFalse(allowed101, "Request #" + (MAX_REQUESTS + 1) + " should be BLOCKED (over limit)");
//    }
//
//    /**
//     * P0-3 Test 3: Different users have independent limits
//     */
//    @Test
//    void shouldHaveSeparateLimitsPerUser() {
//        // Given
//        Long user1 = 100L;
//        Long user2 = 200L;
//
//        // When - User 1 makes 50 requests
//        for (int i = 0; i < 50; i++) {
//            rateLimitService.allowPhotoAccess(user1, IP_ADDRESS);
//        }
//
//        // User 2 makes 80 requests (should still be allowed)
//        for (int i = 0; i < 80; i++) {
//            boolean allowed = rateLimitService.allowPhotoAccess(user2, IP_ADDRESS);
//            assertTrue(allowed, "User 2 request " + (i + 1) + " should be allowed (separate limit)");
//        }
//
//        // Then - User 2 should be at 80, not affected by User 1's 50
//        int user2Count = rateLimitService.getCurrentRequestCount(user2);
//        assertEquals(80, user2Count, "User 2 should have 80 requests, unaffected by User 1");
//
//        // User 1 should still have room for 50 more
//        int user1Remaining = rateLimitService.getRemainingRequests(user1);
//        assertEquals(50, user1Remaining, "User 1 should have 50 requests remaining");
//    }
//
//    /**
//     * P0-3 Test 4: Alert threshold (80% of limit)
//     */
//    @Test
//    void shouldDetectWhenNearRateLimit() {
//        // Given - Get to 80 requests (80% of 100)
//        for (int i = 0; i < 80; i++) {
//            rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//        }
//
//        // When / Then
//        assertTrue(rateLimitService.isNearRateLimit(USER_ID),
//                "Should detect when at 80% of limit");
//
//        // After only 79 requests, should not be in warning zone
//        rateLimitService.resetRateLimit(USER_ID);
//        for (int i = 0; i < 79; i++) {
//            rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//        }
//        assertFalse(rateLimitService.isNearRateLimit(USER_ID),
//                "Should not warn at 79% of limit");
//    }
//
//    /**
//     * P0-3 Test 5: Get remaining requests
//     */
//    @Test
//    void shouldCalculateRemainingRequests() {
//        // Given - Make 30 requests out of 100 max
//        for (int i = 0; i < 30; i++) {
//            rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//        }
//
//        // When
//        int remaining = rateLimitService.getRemainingRequests(USER_ID);
//
//        // Then
//        assertEquals(70, remaining, "Should have 70 requests remaining (100 - 30)");
//    }
//
//    /**
//     * P0-3 Test 6: Reset rate limit (admin function)
//     */
//    @Test
//    void shouldResetRateLimitForUser() {
//        // Given - Exhaust limit
//        for (int i = 0; i < MAX_REQUESTS; i++) {
//            rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//        }
//        boolean blockedBefore = !rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//        assertTrue(blockedBefore, "Should be blocked before reset");
//
//        // When - Reset rate limit
//        rateLimitService.resetRateLimit(USER_ID);
//
//        // Then - Should allow requests again
//        boolean allowedAfter = rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//        assertTrue(allowedAfter, "Should allow requests after reset");
//    }
//
//    /**
//     * P0-3 Test 7: Exact limit boundary
//     */
//    @Test
//    void shouldHandleExactLimitBoundary() {
//        // Given - Make exactly 100 requests
//        for (int i = 0; i < MAX_REQUESTS; i++) {
//            boolean allowed = rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//            assertTrue(allowed, "Request #" + (i + 1) + " should be allowed");
//        }
//
//        // When - Try the 101st request
//        boolean request101 = rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//
//        // Then - Should be blocked
//        assertFalse(request101, "Request #101 should be blocked (over limit)");
//
//        // Verify the count is correct
//        int count = rateLimitService.getCurrentRequestCount(USER_ID);
//        assertEquals(101, count, "Count should be incremented even for blocked request");
//    }
//
//    /**
//     * P0-3 Test 8: Zero remaining requests
//     */
//    @Test
//    void shouldReturnZeroWhenLimitExceeded() {
//        // Given - Exceed the limit
//        for (int i = 0; i <= MAX_REQUESTS; i++) {
//            rateLimitService.allowPhotoAccess(USER_ID, IP_ADDRESS);
//        }
//
//        // When
//        int remaining = rateLimitService.getRemainingRequests(USER_ID);
//
//        // Then - Should be zero or negative
//        assertEquals(0, remaining, "Remaining should be 0 when limit exceeded");
//    }
//}
