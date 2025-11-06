package org.example.chatservice.service;

import lombok.RequiredArgsConstructor;
import org.example.chatservice.dto.client.BookingDetailsDTO;
import org.example.chatservice.dto.client.UserDetailsDTO;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BackendApiClient {

    private final WebClient webClient;

    public Mono<BookingDetailsDTO> getBookingDetails(String bookingId) {
        return webClient.get()
                .uri("/bookings/{bookingId}", bookingId)
                .retrieve()
                .bodyToMono(BookingDetailsDTO.class)
                .onErrorResume(e -> {
                    System.err.println("Error fetching booking details: " + e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<UserDetailsDTO> getUserDetails(String userId) {
        return webClient.get()
                .uri("/users/profile/{userId}", userId)
                .retrieve()
                .bodyToMono(UserDetailsDTO.class)
                .onErrorResume(e -> {
                    System.err.println("Error fetching user details: " + e.getMessage());
                    return Mono.empty();
                });
    }
}
