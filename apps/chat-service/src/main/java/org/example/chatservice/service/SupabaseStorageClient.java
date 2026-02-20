package org.example.chatservice.service;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.exception.StorageUpstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.io.FileNotFoundException;
import java.time.Duration;

/**
 * REST client for Supabase Storage (private bucket access via service role key).
 *
 * <p>Uses Spring {@link WebClient} backed by Reactor Netty. Called synchronously
 * ({@code .block()}) from Spring MVC threads — acceptable because this is an
 * I/O-bound outbound call, not a reactive pipeline.</p>
 *
 * <p>Timeout defaults: 10 s connect, 30 s read (generous for up to 10 MB blobs).</p>
 *
 * <p>Error mapping:</p>
 * <ul>
 *   <li>404 → {@link FileNotFoundException}</li>
 *   <li>4xx → {@link IllegalArgumentException}</li>
 *   <li>5xx / network error → {@link StorageUpstreamException}</li>
 * </ul>
 */
@Component
@Slf4j
public class SupabaseStorageClient {

    /** Sentinel value so the client fails gracefully at call-time rather than startup. */
    private final boolean configured;
    private final WebClient webClient;
    private final String bucket;

    /** Internal marker thrown inside the reactive chain to signal 404 without leaking exception type. */
    private static class ObjectNotFoundException extends RuntimeException {
        ObjectNotFoundException(String key) { super("Not found: " + key); }
    }

    public SupabaseStorageClient(
            @Value("${supabase.url:}") String supabaseUrl,
            @Value("${supabase.service-role-key:}") String serviceRoleKey,
            @Value("${chat.storage.bucket:chat-attachments}") String bucket,
            WebClient.Builder webClientBuilder) {

        this.bucket = bucket;
        this.configured = !supabaseUrl.isBlank() && !serviceRoleKey.isBlank();

        if (!configured) {
            log.warn("[Storage] SupabaseStorageClient not configured: supabase.url or supabase.service-role-key is missing. " +
                    "Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY env vars for production.");
            this.webClient = null;
        } else {
            var httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .responseTimeout(Duration.ofSeconds(30));

            // Do NOT log the service role key — build header silently.
            this.webClient = webClientBuilder
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .baseUrl(supabaseUrl + "/storage/v1")
                    .defaultHeader("Authorization", "Bearer " + serviceRoleKey)
                    .build();

            log.info("[Storage] SupabaseStorageClient ready — bucket='{}', baseUrl={}/storage/v1",
                    bucket, supabaseUrl);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Upload an object to Supabase Storage.
     *
     * <p>Uses {@code PUT /storage/v1/object/{bucket}/{objectKey}} with
     * {@code x-upsert: true} (idempotent — safe to retry after transient failures).</p>
     *
     * @param objectKey   Storage path, e.g. {@code booking-123/550e8400.jpg}
     * @param data        Raw file bytes (must not exceed bucket policy limit)
     * @param contentType Validated MIME type (e.g. {@code image/jpeg})
     * @throws StorageUpstreamException on 5xx or connectivity failure
     * @throws IllegalArgumentException on 4xx client error
     */
    public void upload(String objectKey, byte[] data, String contentType) {
        ensureConfigured();
        String path = "/object/" + bucket + "/" + objectKey;

        try {
            log.debug("[Storage] PUT {} ({} bytes, {})", path, data.length, contentType);

            webClient.put()
                    .uri(path)
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("x-upsert", "true")
                    .bodyValue(data)
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), response ->
                            reactor.core.publisher.Mono.error(new StorageUpstreamException(
                                    "Supabase storage upstream failure: HTTP " + response.statusCode().value())))
                    .toBodilessEntity()
                    .block();

            log.info("[Storage] Uploaded '{}' ({} bytes)", objectKey, data.length);

        } catch (StorageUpstreamException e) {
            throw e;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new StorageUpstreamException(
                        "Supabase storage unavailable: HTTP " + e.getStatusCode().value(), e);
            }
            throw new IllegalArgumentException(
                    "Storage upload rejected: HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            if (e instanceof StorageUpstreamException || e instanceof IllegalArgumentException) throw (RuntimeException) e;
            throw new StorageUpstreamException("Storage upload failed (connectivity)", e);
        }
    }

    /**
     * Download an object from Supabase Storage.
     *
     * <p>Uses {@code GET /storage/v1/object/{bucket}/{objectKey}}. The service role
     * key bypasses RLS so private bucket objects are accessible.</p>
     *
     * @param objectKey Storage path, e.g. {@code booking-123/550e8400.jpg}
     * @return Raw file bytes
     * @throws FileNotFoundException    if the object does not exist (404)
     * @throws StorageUpstreamException on 5xx or connectivity failure
     */
    public byte[] download(String objectKey) throws FileNotFoundException {
        ensureConfigured();
        String path = "/object/" + bucket + "/" + objectKey;

        try {
            log.debug("[Storage] GET {}", path);

            byte[] data = webClient.get()
                    .uri(path)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, response ->
                            reactor.core.publisher.Mono.error(new ObjectNotFoundException(objectKey)))
                    .onStatus(status -> status.is5xxServerError(), response ->
                            reactor.core.publisher.Mono.error(new StorageUpstreamException(
                                    "Supabase storage upstream failure: HTTP " + response.statusCode().value())))
                    .bodyToMono(byte[].class)
                    .block();

            if (data == null || data.length == 0) {
                throw new FileNotFoundException("Attachment not found: " + objectKey);
            }

            log.debug("[Storage] Downloaded '{}' ({} bytes)", objectKey, data.length);
            return data;

        } catch (ObjectNotFoundException e) {
            throw new FileNotFoundException("Attachment not found: " + objectKey);
        } catch (StorageUpstreamException e) {
            throw e;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new FileNotFoundException("Attachment not found: " + objectKey);
            }
            if (e.getStatusCode().is5xxServerError()) {
                throw new StorageUpstreamException(
                        "Supabase storage unavailable: HTTP " + e.getStatusCode().value(), e);
            }
            throw new StorageUpstreamException(
                    "Storage download failed: HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            if (e instanceof StorageUpstreamException sea) throw sea;
            throw new StorageUpstreamException("Storage download failed (connectivity)", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void ensureConfigured() {
        if (!configured) {
            throw new StorageUpstreamException(
                    "Chat storage is not configured. Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY.");
        }
    }
}
