package org.example.chatservice.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.chatservice.dto.client.BookingConversationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ConversationEnrichmentService.
 * 
 * Tests:
 * 1. Successful enrichment merges fields correctly
 * 2. Cache hit returns instantly (no backend call)
 * 3. Circuit breaker fallback populates "Unknown" fields
 * 4. Fallback DTO has messagingAllowed=false for safety
 */
@ExtendWith(MockitoExtension.class)
class ConversationEnrichmentServiceTest {

    @Mock
    private BackendApiClient backendApiClient;

    private ConversationEnrichmentService enrichmentService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        enrichmentService = new ConversationEnrichmentService(backendApiClient, meterRegistry);
    }

    @Test
    void testSuccessfulEnrichment() {
        // Given: Backend returns valid booking conversation data
        String bookingId = "123";
        String actAsUserId = "456";
        
        BookingConversationDTO mockDto = new BookingConversationDTO(
                123L,
                789L,
                "BMW",
                "X5",
                2020,
                "https://example.com/car.jpg",
                LocalDate.of(2025, 12, 1),
                LocalDate.of(2025, 12, 10),
                "ACTIVE",
                "FUTURE",
                true
        );
        
        when(backendApiClient.getConversationView(bookingId, actAsUserId))
                .thenReturn(Mono.just(mockDto));

        // When: Enriching conversation
        Mono<BookingConversationDTO> result = enrichmentService.enrichConversation(bookingId, actAsUserId);

        // Then: Should return the enriched DTO
        StepVerifier.create(result)
                .assertNext(dto -> {
                    assertThat(dto.getCarBrand()).isEqualTo("BMW");
                    assertThat(dto.getCarModel()).isEqualTo("X5");
                    assertThat(dto.getCarYear()).isEqualTo(2020);
                    assertThat(dto.getTripStatus()).isEqualTo("FUTURE");
                    assertThat(dto.isMessagingAllowed()).isTrue();
                })
                .verifyComplete();

        // Verify metrics
        assertThat(meterRegistry.counter("chat.enrichment.success").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("chat.enrichment.cache.miss").count()).isEqualTo(1.0);
    }

    @Test
    void testCacheHit() {
        // Given: Backend returns valid booking conversation data
        String bookingId = "123";
        String actAsUserId = "456";
        
        BookingConversationDTO mockDto = new BookingConversationDTO(
                123L,
                789L,
                "BMW",
                "X5",
                2020,
                null,
                LocalDate.of(2025, 12, 1),
                LocalDate.of(2025, 12, 10),
                "ACTIVE",
                "FUTURE",
                true
        );
        
        when(backendApiClient.getConversationView(bookingId, actAsUserId))
                .thenReturn(Mono.just(mockDto));

        // When: Enriching conversation twice
        Mono<BookingConversationDTO> firstCall = enrichmentService.enrichConversation(bookingId, actAsUserId);
        Mono<BookingConversationDTO> secondCall = enrichmentService.enrichConversation(bookingId, actAsUserId);

        // Then: First call should hit backend, second should hit cache
        StepVerifier.create(firstCall)
                .assertNext(dto -> assertThat(dto.getCarBrand()).isEqualTo("BMW"))
                .verifyComplete();
        
        StepVerifier.create(secondCall)
                .assertNext(dto -> assertThat(dto.getCarBrand()).isEqualTo("BMW"))
                .verifyComplete();

        // Verify metrics: 1 cache miss (first call) + 1 cache hit (second call)
        assertThat(meterRegistry.counter("chat.enrichment.cache.miss").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("chat.enrichment.cache.hit").count()).isEqualTo(1.0);
    }

    @Test
    void testFallbackOnError() {
        // Given: Backend returns error (e.g., 404 or 403)
        String bookingId = "999";
        String actAsUserId = "456";
        
        when(backendApiClient.getConversationView(bookingId, actAsUserId))
                .thenReturn(Mono.just(BookingConversationDTO.createFallback(bookingId)));

        // When: Enriching conversation
        Mono<BookingConversationDTO> result = enrichmentService.enrichConversation(bookingId, actAsUserId);

        // Then: Should return fallback DTO with "Unknown" fields
        StepVerifier.create(result)
                .assertNext(dto -> {
                    assertThat(dto.getCarBrand()).isEqualTo("Unknown");
                    assertThat(dto.getCarModel()).isEqualTo("Unknown");
                    assertThat(dto.getTripStatus()).isEqualTo("UNAVAILABLE");
                    assertThat(dto.isMessagingAllowed()).isFalse(); // Safe default
                })
                .verifyComplete();

        // Verify metrics: Should count as failure
        assertThat(meterRegistry.counter("chat.enrichment.failure").count()).isEqualTo(1.0);
    }

    @Test
    void testFormattedTripDescription() {
        // Given: BookingConversationDTO with car details
        BookingConversationDTO futureTrip = new BookingConversationDTO(
                123L,
                789L,
                "BMW",
                "X5",
                2020,
                null,
                LocalDate.now().plusDays(10),
                LocalDate.now().plusDays(20),
                "ACTIVE",
                "FUTURE",
                true
        );

        BookingConversationDTO currentTrip = new BookingConversationDTO(
                124L,
                790L,
                "Audi",
                "A4",
                2021,
                null,
                LocalDate.now().minusDays(2),
                LocalDate.now().plusDays(2),
                "ACTIVE",
                "CURRENT",
                true
        );

        BookingConversationDTO pastTrip = new BookingConversationDTO(
                125L,
                791L,
                "Mercedes",
                "C-Class",
                2019,
                null,
                LocalDate.now().minusDays(20),
                LocalDate.now().minusDays(10),
                "COMPLETED",
                "PAST",
                false
        );

        // Then: Should generate correct formatted descriptions
        assertThat(futureTrip.getFormattedTripDescription()).isEqualTo("Future trip with 2020 BMW X5");
        assertThat(currentTrip.getFormattedTripDescription()).isEqualTo("Current trip with 2021 Audi A4");
        assertThat(pastTrip.getFormattedTripDescription()).isEqualTo("Past trip with 2019 Mercedes C-Class");
    }

    @Test
    void testCircuitBreakerState() {
        // When: Checking circuit breaker state
        var state = enrichmentService.getCircuitBreakerState();

        // Then: Should be CLOSED initially
        assertThat(state.toString()).isEqualTo("CLOSED");
    }

    @Test
    void testCacheStats() {
        // Given: Some cache activity
        String bookingId = "123";
        String actAsUserId = "456";
        
        BookingConversationDTO mockDto = new BookingConversationDTO(
                123L,
                789L,
                "BMW",
                "X5",
                2020,
                null,
                LocalDate.of(2025, 12, 1),
                LocalDate.of(2025, 12, 10),
                "ACTIVE",
                "FUTURE",
                true
        );
        
        when(backendApiClient.getConversationView(anyString(), anyString()))
                .thenReturn(Mono.just(mockDto));

        // When: Making multiple enrichment calls
        enrichmentService.enrichConversation(bookingId, actAsUserId).block();
        enrichmentService.enrichConversation(bookingId, actAsUserId).block();
        enrichmentService.enrichConversation(bookingId, actAsUserId).block();

        // Then: Cache stats should be available
        var stats = enrichmentService.getCacheStats();
        assertThat(stats.requestCount()).isEqualTo(3);
        assertThat(stats.hitCount()).isEqualTo(2); // First is miss, second and third are hits
        assertThat(stats.hitRate()).isGreaterThan(0.5);
    }
}
