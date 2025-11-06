package org.example.rentoza.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationResponse {
    private Long id;
    private String bookingId;
    private String renterId;
    private String ownerId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean messagingAllowed;
}
