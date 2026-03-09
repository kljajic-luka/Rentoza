package org.example.chatservice.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAuditEntry {

    public static final int MAX_USER_AGENT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    // AUDIT-H2-FIX: Store operator justification for sensitive oversight access and actions.
    @Column(name = "justification", columnDefinition = "TEXT")
    private String justification;

    // AUDIT-H2-FIX: Capture client IP for insider-abuse forensics.
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // AUDIT-H2-FIX: Capture user agent for insider-abuse forensics.
    @Column(name = "user_agent", length = MAX_USER_AGENT_LENGTH)
    private String userAgent;

    // AUDIT-H2-FIX: Persist the outcome of the oversight access or action.
    @Column(name = "result", length = 50)
    private String result;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    public enum Action {
        CONVERSATIONS_LISTED,
        FLAGGED_MESSAGES_VIEWED,
        REVIEW_DISMISSED,
        REVIEW_ACTIONED,
        CONVERSATION_VIEWED,
        TRANSCRIPT_EXPORTED
    }

    public enum TargetType {
        MESSAGE,
        CONVERSATION
    }
}