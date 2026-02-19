package org.example.rentoza.review;

import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.security.JwtUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Controller-level tests for ReviewController.
 * P0-3: Verifies legacy endpoint returns HTTP 410 Gone.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewController - Legacy Endpoint Deprecation")
class ReviewControllerTest {

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private ReviewController controller;

    private JwtUserPrincipal mockPrincipal;

    @BeforeEach
    void setUp() {
        mockPrincipal = mock(JwtUserPrincipal.class);
    }

    @Test
    @DisplayName("P0-3: Legacy POST /api/reviews should return HTTP 410 Gone")
    @SuppressWarnings("deprecation")
    void legacyEndpointShouldReturn410() {
        ReviewRequestDTO dto = new ReviewRequestDTO();
        dto.setRating(5);
        dto.setComment("Test");

        ResponseEntity<?> response = controller.addReview(dto, mockPrincipal);

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("error");
        assertThat(body.get("error").toString()).containsIgnoringCase("deprecated");

        // Verify the review service was never called
        verifyNoInteractions(reviewService);
    }
}
