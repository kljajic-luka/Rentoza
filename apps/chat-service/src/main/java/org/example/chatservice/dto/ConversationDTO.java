package org.example.chatservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.chatservice.model.ConversationStatus;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDTO {

    private Long id;
    private Long bookingId;  // BIGINT in SQL
    private Long renterId;
    private Long ownerId;
    private ConversationStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime lastMessageAt;
    private List<MessageDTO> messages;
    private long unreadCount;
    private boolean messagingAllowed;

    // Extended fields for UI context
    private String carBrand;
    private String carModel;
    private Integer carYear;
    private String carImageUrl;
    private String renterName;
    private String ownerName;
    private String renterProfilePicUrl;
    private String ownerProfilePicUrl;
    private String startDate;
    private String endDate;
    private String lastMessageContent; // Preview of the last message for conversation list
    private String tripStatus; // "FUTURE", "CURRENT", "PAST", "UNAVAILABLE"
}
