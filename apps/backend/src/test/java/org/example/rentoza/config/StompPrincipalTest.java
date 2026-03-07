package org.example.rentoza.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StompPrincipalTest {

    @Test
    @DisplayName("getName returns the websocket user identifier")
    void getNameReturnsUserId() {
        StompPrincipal principal = new StompPrincipal("user-123");

        assertThat(principal.getName()).isEqualTo("user-123");
    }
}