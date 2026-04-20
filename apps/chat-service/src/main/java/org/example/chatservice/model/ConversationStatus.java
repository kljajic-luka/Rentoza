package org.example.chatservice.model;

public enum ConversationStatus {
    PENDING,    // Booking requested, chat available but limited
    ACTIVE,     // Booking confirmed, full chat access
    CLOSED      // Trip completed or canceled, conversation remains available
}
