package org.example.rentoza.user.gdpr;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Entity for tracking user consent history (GDPR Article 7).
 */
@Entity
@Table(name = "user_consents")
@Data
public class UserConsent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @Column(name = "consent_type", nullable = false)
    private String consentType;
    
    @Column(name = "granted", nullable = false)
    private boolean granted;
    
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;
    
    @Column(name = "ip_address")
    private String ipAddress;
    
    @Column(name = "user_agent")
    private String userAgent;
}
