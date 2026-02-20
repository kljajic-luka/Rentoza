package org.example.chatservice.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.example.chatservice.exception.StorageUpstreamException;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.FileNotFoundException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

/**
 * HTTP-level tests for {@link SupabaseStorageClient} using {@link MockWebServer}.
 *
 * <p>We instantiate the client directly (no Spring context) and point it at the
 * mock server so we can verify exact HTTP requests and simulate Supabase responses.</p>
 */
class SupabaseStorageClientTest {

    private MockWebServer server;
    private SupabaseStorageClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("").toString();
        // Strip trailing slash — SupabaseStorageClient appends "/storage/v1" itself
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        client = new SupabaseStorageClient(baseUrl, "test-service-role-key",
                "test-bucket", WebClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // -------------------------------------------------------------------------
    // upload()
    // -------------------------------------------------------------------------

    @Test
    void upload_sendsCorrectRequest() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"Key\":\"test-bucket/booking-1/file.jpg\"}"));

        byte[] data = {(byte) 0xFF, (byte) 0xD8, 0x00, 0x01};
        client.upload("booking-1/file.jpg", data, "image/jpeg");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/storage/v1/object/test-bucket/booking-1/file.jpg");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-service-role-key");
        assertThat(request.getHeader("x-upsert")).isEqualTo("true");
        assertThat(request.getHeader("Content-Type")).startsWith("image/jpeg");
        assertThat(request.getBody().readByteArray()).isEqualTo(data);
    }

    @Test
    void upload_500_throwsStorageUpstreamException() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        byte[] data = {1, 2, 3};
        assertThatThrownBy(() -> client.upload("booking-1/file.jpg", data, "image/jpeg"))
                .isInstanceOf(StorageUpstreamException.class);
    }

    @Test
    void upload_400_throwsIllegalArgumentException() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid\"}"));

        byte[] data = {1, 2, 3};
        assertThatThrownBy(() -> client.upload("booking-1/file.jpg", data, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // download()
    // -------------------------------------------------------------------------

    @Test
    void download_returnsBytes() throws Exception {
        byte[] expected = {(byte) 0xFF, (byte) 0xD8, 0x00, 0x01};
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(new okio.Buffer().write(expected))
                .setHeader("Content-Type", "image/jpeg"));

        byte[] result = client.download("booking-1/file.jpg");
        assertThat(result).isEqualTo(expected);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/storage/v1/object/test-bucket/booking-1/file.jpg");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-service-role-key");
    }

    @Test
    void download_404_throwsFileNotFoundException() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"error\":\"not found\"}"));

        assertThatThrownBy(() -> client.download("booking-1/missing.jpg"))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("missing.jpg");
    }

    @Test
    void download_500_throwsStorageUpstreamException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        assertThatThrownBy(() -> client.download("booking-1/file.jpg"))
                .isInstanceOf(StorageUpstreamException.class);
    }

    // -------------------------------------------------------------------------
    // not-configured guard
    // -------------------------------------------------------------------------

    @Test
    void notConfigured_throwsStorageUpstreamException() throws IOException {
        // Client with blank credentials is not "configured"
        SupabaseStorageClient unconfigured = new SupabaseStorageClient("", "",
                "bucket", WebClient.builder());

        assertThatThrownBy(() -> unconfigured.upload("key", new byte[]{1}, "image/jpeg"))
                .isInstanceOf(StorageUpstreamException.class)
                .hasMessageContaining("not configured");

        assertThatThrownBy(() -> unconfigured.download("key"))
                .isInstanceOf(StorageUpstreamException.class)
                .hasMessageContaining("not configured");
    }
}
