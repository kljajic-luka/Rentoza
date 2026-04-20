package org.example.rentoza.user.gdpr;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Entry in the data access log.
 */
@Data
@AllArgsConstructor
public class DataAccessLogEntry {
    private LocalDateTime timestamp;
    private String action;
    private String description;
    private String source;
}
