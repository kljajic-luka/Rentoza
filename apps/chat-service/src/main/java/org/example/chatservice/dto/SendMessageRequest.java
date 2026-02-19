package org.example.chatservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {

    @NotBlank(message = "Message content cannot be empty")
    @Size(max = 2000, message = "Message cannot exceed 2000 characters")
    private String content;

    /**
     * Optional attachment URL. Must be a platform attachment path
     * (i.e., start with /api/attachments/booking-). Arbitrary external
     * URLs are rejected to prevent injection/phishing.
     */
    @jakarta.validation.constraints.Pattern(
            regexp = "^/api/attachments/booking-\\d+/.+$",
            message = "Invalid media URL: must be a platform attachment")
    private String mediaUrl;
}
