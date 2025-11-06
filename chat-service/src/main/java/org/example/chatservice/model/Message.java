package org.example.chatservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long conversationId;

    @Column(nullable = false)
    private String senderId;

    @Column(nullable = false, length = 2000)
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "message_read_by", joinColumns = @JoinColumn(name = "message_id"))
    @Column(name = "user_id")
    @Builder.Default
    private Set<String> readBy = new HashSet<>();

    @Column
    private String mediaUrl;

    // Mark message as read by a user
    public void markAsReadBy(String userId) {
        if (readBy == null) {
            readBy = new HashSet<>();
        }
        readBy.add(userId);
    }

    // Check if message is read by a user
    public boolean isReadBy(String userId) {
        return readBy != null && readBy.contains(userId);
    }
}
