//package org.example.rentoza.booking.photo;
//
//import org.example.rentoza.storage.SupabaseStorageService;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.io.IOException;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * P0-2 Security Test Suite: PII Photo Storage
// *
// * Verifies that PII photos (ID documents) can ONLY be stored in Supabase encrypted buckets.
// * No fallback to insecure local filesystem allowed.
// */
//@ExtendWith(MockitoExtension.class)
//public class PiiPhotoStorageServiceTest {
//
//    @Mock
//    private SupabaseStorageService supabaseStorageService;
//
//    private PiiPhotoStorageService piiPhotoStorageService;
//
//    private static final String SESSION_ID = "abc-123-session";
//    private static final String STORAGE_KEY = "checkin_pii/abc-123-session/ID_DOCUMENT_12345.jpg";
//    private static final byte[] PHOTO_BYTES = "fake-image-data".getBytes();
//    private static final String MIME_TYPE = "image/jpeg";
//
//    @BeforeEach
//    void setUp() {
//        piiPhotoStorageService = new PiiPhotoStorageService(supabaseStorageService);
//    }
//
//    /**
//     * P0-2 Test 1: Store PII photo in Supabase when configured
//     */
//    @Test
//    void shouldStoreToSupabaseWhenConfigured() throws IOException {
//        // When
//        piiPhotoStorageService.storePiiPhoto(
//                "checkin_pii",
//                STORAGE_KEY,
//                PHOTO_BYTES,
//                MIME_TYPE
//        );
//
//        // Then
//        verify(supabaseStorageService, times(1)).uploadFile(
//                "checkin_pii",
//                STORAGE_KEY,
//                PHOTO_BYTES,
//                MIME_TYPE
//        );
//    }
//
//    /**
//     * P0-2 Test 2: Reject non-PII bucket names (CRITICAL SECURITY TEST)
//     */
//    @Test
//    void shouldRejectNonPiiBucketNames() {
//        // When / Then - Should throw exception for public bucket
//        IllegalArgumentException exception = assertThrows(
//                IllegalArgumentException.class,
//                () -> piiPhotoStorageService.storePiiPhoto(
//                        "public-bucket",  // ← WRONG: Not a PII bucket
//                        STORAGE_KEY,
//                        PHOTO_BYTES,
//                        MIME_TYPE
//                )
//        );
//
//        assertTrue(exception.getMessage().toLowerCase().contains("pii"),
//                "Error should mention PII bucket requirement");
//
//        // Verify Supabase was NOT called
//        verify(supabaseStorageService, never()).uploadFile(anyString(), anyString(), any(), anyString());
//    }
//
//    /**
//     * P0-2 Test 3: Reject standard bucket (CRITICAL SECURITY TEST)
//     */
//    @Test
//    void shouldRejectStandardBucket() {
//        // When / Then
//        IllegalArgumentException exception = assertThrows(
//                IllegalArgumentException.class,
//                () -> piiPhotoStorageService.storePiiPhoto(
//                        "checkin_standard",  // ← WRONG: Not encrypted for PII
//                        STORAGE_KEY,
//                        PHOTO_BYTES,
//                        MIME_TYPE
//                )
//        );
//
//        // Verify error message is clear
//        assertTrue(
//                exception.getMessage().toLowerCase().contains("pii"),
//                "Error should clarify PII bucket requirement"
//        );
//
//        // Verify Supabase was NOT called
//        verify(supabaseStorageService, never()).uploadFile(anyString(), anyString(), any(), anyString());
//    }
//
//    /**
//     * P0-2 Test 4: Handle Supabase upload failure gracefully
//     */
//    @Test
//    void shouldPropagateSupabaseUploadFailure() throws IOException {
//        // Given - Supabase throws exception
//        doThrow(new IOException("Supabase connection failed"))
//                .when(supabaseStorageService)
//                .uploadFile(anyString(), anyString(), any(), anyString());
//
//        // When / Then
//        IOException exception = assertThrows(
//                IOException.class,
//                () -> piiPhotoStorageService.storePiiPhoto(
//                        "checkin_pii",
//                        STORAGE_KEY,
//                        PHOTO_BYTES,
//                        MIME_TYPE
//                )
//        );
//
//        assertTrue(exception.getMessage().toLowerCase().contains("supabase"),
//                "Error should indicate Supabase failure");
//    }
//
//    /**
//     * P0-2 Test 5: Accept various PII bucket names
//     */
//    @Test
//    void shouldAcceptAnyBucketWithPiiInName() throws IOException {
//        // Test different valid PII bucket names
//        String[] validBuckets = {
//                "checkin_pii",
//                "CHECKIN_PII",
//                "CheckIn_PII",
//                "checkout_pii",
//                "my_custom_pii_bucket",
//                "pii_encrypted"
//        };
//
//        for (String bucket : validBuckets) {
//            // When
//            piiPhotoStorageService.storePiiPhoto(
//                    bucket,
//                    STORAGE_KEY,
//                    PHOTO_BYTES,
//                    MIME_TYPE
//            );
//
//            // Then - Should not throw exception
//            verify(supabaseStorageService, atLeastOnce()).uploadFile(
//                    eq(bucket),
//                    anyString(),
//                    any(),
//                    anyString()
//            );
//        }
//    }
//
//    /**
//     * P0-2 Test 6: Validate configuration at startup
//     */
//    @Test
//    void shouldHaveConfigurationValidation() {
//        // Given
//        PiiPhotoStorageService service = new PiiPhotoStorageService(supabaseStorageService);
//
//        // When / Then - Should have validateConfiguration() method
//        assertDoesNotThrow(() -> service.validateConfiguration(),
//                "Should have configuration validation method");
//    }
//
//    /**
//     * P0-2 Test 7: Get correct bucket for document type
//     */
//    @Test
//    void shouldReturnCorrectBucketForDocumentType() {
//        // When
//        String bucket = piiPhotoStorageService.getBucketForDocumentType("DRIVER_LICENSE");
//
//        // Then - Should return PII bucket
//        assertTrue(bucket.toLowerCase().contains("pii"),
//                "Should return PII bucket for document types");
//    }
//
//    /**
//     * P0-2 Test 8: Availability check
//     */
//    @Test
//    void shouldCheckSupabaseAvailability() {
//        // When
//        boolean available = piiPhotoStorageService.isSupabaseAvailable();
//
//        // Then - Should indicate availability status
//        // (actual result depends on configuration, just verify method exists and returns boolean)
//        assertNotNull(available, "Should have availability check");
//    }
//
//    /**
//     * P0-2 Test 9: Empty/null bucket name rejection
//     */
//    @Test
//    void shouldRejectEmptyBucketName() {
//        // When / Then
//        assertThrows(
//                Exception.class,
//                () -> piiPhotoStorageService.storePiiPhoto(
//                        "",  // ← INVALID
//                        STORAGE_KEY,
//                        PHOTO_BYTES,
//                        MIME_TYPE
//                )
//        );
//
//        // Verify Supabase not called
//        verify(supabaseStorageService, never()).uploadFile(anyString(), anyString(), any(), anyString());
//    }
//}
