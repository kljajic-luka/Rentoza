package org.example.chatservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for typing indicator WebSocket messages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TypingIndicatorDTO {
    
    /**
     * User ID of the person typing.
     * Set by server from authenticated principal (not trusted from client).
     */
    private Long userId;

    /**
     * True if user is currently typing, false if they stopped.
     */
    private boolean typing;

    /**
     * Display name of the user (optional, for UI).
     */
    private String displayName;
}
