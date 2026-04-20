package org.example.rentoza.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for R6: Write-once storage semantics on evidence buckets.
 *
 * <p>Verifies that evidence buckets (checkin-audit, check-in-photos, check-in-pii)
 * use {@code x-upsert: false} to prevent silent evidence replacement, while
 * non-evidence buckets (car images, user avatars) retain upsert capability.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("R6: Evidence Bucket Write-Once Semantics")
class SupabaseStorageServiceR6Test {

    private SupabaseStorageService storageService;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        storageService = new SupabaseStorageService();
        restTemplate = mock(RestTemplate.class);

        ReflectionTestUtils.setField(storageService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(storageService, "supabaseUrl", "https://test.supabase.co");
        ReflectionTestUtils.setField(storageService, "serviceRoleKey", "test-key");

        // All uploads succeed
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));
    }

    @Nested
    @DisplayName("Evidence Buckets: x-upsert=false")
    class EvidenceBucketTests {

        @Test
        @DisplayName("Audit bucket upload uses x-upsert=false")
        void auditBucketShouldUseUpsertFalse() throws Exception {
            byte[] photoBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};

            storageService.uploadCheckInPhotoToAuditBucket(1L, "host", "EXTERIOR_FRONT", photoBytes, "image/jpeg");

            ArgumentCaptor<HttpEntity<byte[]>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), requestCaptor.capture(), eq(String.class));

            HttpHeaders headers = requestCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("x-upsert")).isEqualTo("false");
        }

        @Test
        @DisplayName("Check-in photo bytes upload uses x-upsert=false")
        void checkInPhotoBytesUploadShouldUseUpsertFalse() throws Exception {
            byte[] photoBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};

            storageService.uploadCheckInPhotoBytes(1L, "host", "EXTERIOR_FRONT", photoBytes, "image/jpeg");

            ArgumentCaptor<HttpEntity<byte[]>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), requestCaptor.capture(), eq(String.class));

            HttpHeaders headers = requestCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("x-upsert")).isEqualTo("false");
        }

        @Test
        @DisplayName("PII photo upload uses x-upsert=false")
        void piiPhotoUploadShouldUseUpsertFalse() throws Exception {
            byte[] photoBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};

            storageService.uploadIdPhoto("test-session/id_photo.jpg", photoBytes, "image/jpeg");

            ArgumentCaptor<HttpEntity<byte[]>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), requestCaptor.capture(), eq(String.class));

            HttpHeaders headers = requestCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("x-upsert")).isEqualTo("false");
        }
    }

    @Nested
    @DisplayName("Non-Evidence Buckets: x-upsert=true (backward compatible)")
    class NonEvidenceBucketTests {

        @Test
        @DisplayName("Car image upload retains x-upsert=true")
        void carImageUploadShouldUseUpsertTrue() throws Exception {
            org.springframework.mock.web.MockMultipartFile file =
                    new org.springframework.mock.web.MockMultipartFile(
                            "file", "car.jpg", "image/jpeg",
                            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01});

            storageService.uploadCarImage(1L, file);

            ArgumentCaptor<HttpEntity<byte[]>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), requestCaptor.capture(), eq(String.class));

            HttpHeaders headers = requestCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("x-upsert")).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("SHA-256 calculation produces consistent results")
    void sha256ShouldBeConsistent() {
        byte[] data = "test data for hash verification".getBytes();

        String hash1 = storageService.calculateSha256(data);
        String hash2 = storageService.calculateSha256(data);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
        assertThat(hash1).matches("[0-9a-f]{64}");
    }
}
