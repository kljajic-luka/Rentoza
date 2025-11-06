package org.example.chatservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {

    private Long id;
    private Long conversationId;
    private String senderId;
    private String content;
    private LocalDateTime timestamp;
    private Set<String> readBy;
    private String mediaUrl;
    private boolean isOwnMessage;

    // Message status tracking
    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
}
