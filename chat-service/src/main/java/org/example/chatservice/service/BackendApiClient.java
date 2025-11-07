package org.example.chatservice.service;

import lombok.RequiredArgsConstructor;
import org.example.chatservice.dto.client.BookingDetailsDTO;
import org.example.chatservice.dto.client.UserDetailsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BackendApiClient {

    private static final Logger logger = LoggerFactory.getLogger(BackendApiClient.class);

    private final WebClient webClient;

    public Mono<BookingDetailsDTO> getBookingDetails(String bookingId) {
        logger.info("🔄 Fetching booking details for bookingId: {}", bookingId);
        
        return webClient.get()
                .uri("/api/bookings/{bookingId}", bookingId)
                .retrieve()
                .onStatus(
                    status -> status == HttpStatus.NOT_FOUND,
                    response -> {
                        logger.warn("⚠️ Booking [id={}] not found in backend (404) — will use fallback", bookingId);
                        return Mono.empty();
                    }
                )
                .bodyToMono(BookingDetailsDTO.class)
                .doOnSuccess(response -> {
                    if (response != null) {
                        logger.info("✅ Successfully enriched booking [{}] with real backend data", bookingId);
                    }
                })
                .doOnError(error -> {
                    if (!(error instanceof WebClientResponseException.NotFound)) {
                        logger.error("❌ Failed to fetch booking details for bookingId: {}. Error: {}", 
                                bookingId, error.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof WebClientResponseException.NotFound) {
                        logger.warn("⚠️ Booking [id={}] not found on main backend — returning fallback DTO", bookingId);
                    } else {
                        logger.warn("⚠️ Error fetching booking [id={}] — returning fallback DTO: {}", 
                                bookingId, e.getMessage());
                    }
                    return Mono.just(BookingDetailsDTO.createFallback(bookingId));
                });
    }

    public Mono<UserDetailsDTO> getUserDetails(String userId) {
        logger.info("🔄 Fetching user details for userId: {}", userId);
        
        return webClient.get()
                .uri("/api/users/profile/{userId}", userId)
                .retrieve()
                .onStatus(
                    status -> status == HttpStatus.NOT_FOUND,
                    response -> {
                        logger.warn("⚠️ User [id={}] not found in backend", userId);
                        return Mono.empty();
                    }
                )
                .bodyToMono(UserDetailsDTO.class)
                .doOnSuccess(response -> {
                    if (response != null) {
                        logger.info("✅ Successfully fetched user details for userId: {}", userId);
                    }
                })
                .doOnError(error -> {
                    if (!(error instanceof WebClientResponseException.NotFound)) {
                        logger.error("❌ Failed to fetch user details for userId: {}. Error: {}", 
                                userId, error.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    logger.warn("⚠️ Returning empty user details due to error for userId: {}", userId);
                    return Mono.empty();
                });
    }
}
