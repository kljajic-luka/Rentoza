package org.example.chatservice.service;

import org.example.chatservice.exception.StorageUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FileStorageService}.
 *
 * <p>All tests run the {@code supabase} provider path unless the test itself
 * switches to {@code local} via {@link ReflectionTestUtils}.</p>
 */
@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @Mock
    private SupabaseStorageClient storageClient;

    @InjectMocks
    private FileStorageService service;

    // Magic byte prefixes
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, 0x00, 0x01};
    private static final byte[] PNG_MAGIC  = {(byte) 0x89, (byte) 0x50, (byte) 0x4E,
                                               (byte) 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF_MAGIC  = {(byte) 0x47, (byte) 0x49, (byte) 0x46,
                                               (byte) 0x38, (byte) 0x39, (byte) 0x61};
    private static final byte[] PDF_MAGIC  = {(byte) 0x25, (byte) 0x50, (byte) 0x44,
                                               (byte) 0x46, 0x2D, 0x31, 0x2E, 0x32};

    @BeforeEach
    void injectProperties() {
        ReflectionTestUtils.setField(service, "storageProvider", "supabase");
        ReflectionTestUtils.setField(service, "uploadDirectory", "/tmp/chat-test-uploads");
        ReflectionTestUtils.setField(service, "baseUrl", "/api/attachments");
    }

    // -------------------------------------------------------------------------
    // uploadAttachment — success paths
    // -------------------------------------------------------------------------

    @Test
    void uploadAttachment_validJpeg_callsSupabaseAndReturnsUrl() {
        doNothing().when(storageClient).upload(anyString(), any(), eq("image/jpeg"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_MAGIC);

        String url = service.uploadAttachment(file, 42L, 7L);

        verify(storageClient).upload(argThat(key -> key.startsWith("booking-42/") && key.endsWith(".jpg")),
                any(), eq("image/jpeg"));
        assertThat(url).startsWith("/api/attachments/booking-42/").endsWith(".jpg");
    }

    @Test
    void uploadAttachment_validPng_succeeds() {
        doNothing().when(storageClient).upload(anyString(), any(), eq("image/png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", PNG_MAGIC);

        String url = service.uploadAttachment(file, 1L, 1L);
        assertThat(url).endsWith(".png");
    }

    @Test
    void uploadAttachment_validGif_succeeds() {
        doNothing().when(storageClient).upload(anyString(), any(), eq("image/gif"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "anim.gif", "image/gif", GIF_MAGIC);

        String url = service.uploadAttachment(file, 1L, 1L);
        assertThat(url).endsWith(".gif");
    }

    @Test
    void uploadAttachment_validPdf_succeeds() {
        doNothing().when(storageClient).upload(anyString(), any(), eq("application/pdf"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", PDF_MAGIC);

        String url = service.uploadAttachment(file, 5L, 3L);
        assertThat(url).endsWith(".pdf");
    }

    // -------------------------------------------------------------------------
    // uploadAttachment — validation failures
    // -------------------------------------------------------------------------

    @Test
    void uploadAttachment_emptyFile_throwsIllegalArgument() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.jpg", "image/jpeg", new byte[0]);
        assertThatThrownBy(() -> service.uploadAttachment(empty, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void uploadAttachment_nullFile_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.uploadAttachment(null, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadAttachment_tooLarge_throwsIllegalArgument() {
        // Build content larger than 10 MB
        byte[] big = new byte[(int) (FileStorageService.getMaxFileSize() + 1)];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; // JPEG magic (won't reach scan)
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.jpg", "image/jpeg", big);

        assertThatThrownBy(() -> service.uploadAttachment(file, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("large");
    }

    @Test
    void uploadAttachment_blockedMimeType_throwsIllegalArgument() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.exe", "application/octet-stream",
                "MZ\0\0".getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> service.uploadAttachment(file, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid file type");
    }

    @Test
    void uploadAttachment_blockedExtension_throwsIllegalArgument() {
        // MIME is "allowed" but extension is not
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.svg", "image/jpeg", JPEG_MAGIC);

        assertThatThrownBy(() -> service.uploadAttachment(file, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension");
    }

    @Test
    void uploadAttachment_magicByteMismatch_throwsIllegalArgument() {
        // Claims to be JPEG but content looks like HTML
        byte[] fakeJpeg = "<html>malicious</html>".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.jpg", "image/jpeg", fakeJpeg);

        assertThatThrownBy(() -> service.uploadAttachment(file, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content does not match");
    }

    @Test
    void uploadAttachment_supabaseUpstreamFailure_propagates() {
        doThrow(new StorageUpstreamException("S3 down"))
                .when(storageClient).upload(anyString(), any(), anyString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_MAGIC);

        assertThatThrownBy(() -> service.uploadAttachment(file, 1L, 1L))
                .isInstanceOf(StorageUpstreamException.class)
                .hasMessageContaining("S3 down");
    }

    // -------------------------------------------------------------------------
    // getFile
    // -------------------------------------------------------------------------

    @Test
    void getFile_supabaseProvider_delegatesToClient() throws Exception {
        byte[] expected = JPEG_MAGIC;
        when(storageClient.download("booking-5/abc.jpg")).thenReturn(expected);

        byte[] result = service.getFile("booking-5/abc.jpg");
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getFile_pathTraversal_throwsSecurityException() {
        assertThatThrownBy(() -> service.getFile("../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getFile_nullByteInPath_throwsSecurityException() {
        assertThatThrownBy(() -> service.getFile("booking-1/\0etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getFile_supabaseNotFound_throwsFileNotFoundException() throws Exception {
        when(storageClient.download("booking-1/missing.jpg"))
                .thenThrow(new FileNotFoundException("Attachment not found: booking-1/missing.jpg"));

        assertThatThrownBy(() -> service.getFile("booking-1/missing.jpg"))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void getFile_supabaseUpstream_throwsStorageUpstreamException() throws Exception {
        when(storageClient.download(anyString()))
                .thenThrow(new StorageUpstreamException("timeout"));

        assertThatThrownBy(() -> service.getFile("booking-1/file.jpg"))
                .isInstanceOf(StorageUpstreamException.class);
    }

    // -------------------------------------------------------------------------
    // getContentType
    // -------------------------------------------------------------------------

    @Test
    void getContentType_jpeg_returnsCorrectMime() {
        assertThat(service.getContentType("booking-1/photo.jpg")).isEqualTo("image/jpeg");
        assertThat(service.getContentType("booking-1/photo.jpeg")).isEqualTo("image/jpeg");
    }

    @Test
    void getContentType_png_returnsCorrectMime() {
        assertThat(service.getContentType("booking-1/image.png")).isEqualTo("image/png");
    }

    @Test
    void getContentType_pdf_returnsCorrectMime() {
        assertThat(service.getContentType("booking-1/doc.pdf")).isEqualTo("application/pdf");
    }

    @Test
    void getContentType_unknown_returnsOctetStream() {
        assertThat(service.getContentType("booking-1/file.xyz")).isEqualTo("application/octet-stream");
    }

    @Test
    void getContentType_noExtension_returnsOctetStream() {
        assertThat(service.getContentType("booking-1/README")).isEqualTo("application/octet-stream");
    }

    // -------------------------------------------------------------------------
    // Static contract methods
    // -------------------------------------------------------------------------

    @Test
    void maxFileSizeIs10Mb() {
        assertThat(FileStorageService.getMaxFileSize()).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    void allowedMimeTypes_containsExpected() {
        var types = FileStorageService.getAllowedMimeTypes();
        assertThat(types).contains("image/jpeg", "image/png", "image/gif",
                "image/webp", "application/pdf");
    }
}
