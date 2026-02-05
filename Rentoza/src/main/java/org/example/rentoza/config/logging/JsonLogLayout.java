package org.example.rentoza.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Custom Logback layout for JSON structured logging.
 * 
 * <h2>Output Format</h2>
 * <pre>
 * {
 *   "timestamp": "2026-02-04T18:00:00.000Z",
 *   "level": "INFO",
 *   "logger": "org.example.rentoza.booking.BookingService",
 *   "message": "Booking created successfully",
 *   "requestId": "REQ-a1b2c3d4",
 *   "correlationId": "client-corr-123",
 *   "userId": "42",
 *   "path": "/api/bookings",
 *   "method": "POST",
 *   "thread": "http-nio-8080-exec-1",
 *   "exception": "java.lang.NullPointerException: ...",
 *   "service": "rentoza-backend",
 *   "environment": "production"
 * }
 * </pre>
 * 
 * <h2>Google Cloud Logging Integration</h2>
 * <p>This format is compatible with Google Cloud Logging's structured logging:
 * <ul>
 *   <li>Automatic severity detection from "level" field</li>
 *   <li>Request correlation via "requestId"</li>
 *   <li>Stack trace extraction from "exception" field</li>
 *   <li>User context for debugging via "userId"</li>
 * </ul>
 * 
 * @since Phase 5 - Reliability & Monitoring
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_FORMATTER = 
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));
    
    private String serviceName = "rentoza-backend";
    private String environment = "development";
    
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }
    
    public void setEnvironment(String environment) {
        this.environment = environment;
    }
    
    @Override
    public String doLayout(ILoggingEvent event) {
        ObjectNode json = MAPPER.createObjectNode();
        
        // Timestamp in ISO-8601 format
        json.put("timestamp", ISO_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())));
        
        // Log level (Google Cloud uses "severity")
        json.put("level", event.getLevel().toString());
        json.put("severity", mapToCloudSeverity(event.getLevel().toString()));
        
        // Logger name (shortened for readability)
        json.put("logger", shortenLoggerName(event.getLoggerName()));
        
        // The actual log message
        json.put("message", event.getFormattedMessage());
        
        // Thread info
        json.put("thread", event.getThreadName());
        
        // MDC context (requestId, userId, etc.)
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && !mdc.isEmpty()) {
            if (mdc.containsKey("requestId")) {
                json.put("requestId", mdc.get("requestId"));
            }
            if (mdc.containsKey("correlationId")) {
                json.put("correlationId", mdc.get("correlationId"));
            }
            if (mdc.containsKey("userId")) {
                json.put("userId", mdc.get("userId"));
            }
            if (mdc.containsKey("path")) {
                json.put("path", mdc.get("path"));
            }
            if (mdc.containsKey("method")) {
                json.put("method", mdc.get("method"));
            }
        }
        
        // Exception info if present
        if (event.getThrowableProxy() != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(event.getThrowableProxy().getClassName())
              .append(": ")
              .append(event.getThrowableProxy().getMessage());
            
            // Include first 5 stack frames
            var stackFrames = event.getThrowableProxy().getStackTraceElementProxyArray();
            if (stackFrames != null) {
                int limit = Math.min(5, stackFrames.length);
                for (int i = 0; i < limit; i++) {
                    sb.append("\n\t").append(stackFrames[i].getSTEAsString());
                }
                if (stackFrames.length > limit) {
                    sb.append("\n\t... ").append(stackFrames.length - limit).append(" more");
                }
            }
            json.put("exception", sb.toString());
        }
        
        // Service metadata
        json.put("service", serviceName);
        json.put("environment", environment);
        
        try {
            return MAPPER.writeValueAsString(json) + "\n";
        } catch (JsonProcessingException e) {
            // Fallback to simple format if JSON fails
            return String.format("%s [%s] %s - %s%n",
                    event.getTimeStamp(),
                    event.getLevel(),
                    event.getLoggerName(),
                    event.getFormattedMessage());
        }
    }
    
    /**
     * Map Logback levels to Google Cloud Logging severity levels.
     */
    private String mapToCloudSeverity(String level) {
        return switch (level) {
            case "TRACE", "DEBUG" -> "DEBUG";
            case "INFO" -> "INFO";
            case "WARN" -> "WARNING";
            case "ERROR" -> "ERROR";
            default -> "DEFAULT";
        };
    }
    
    /**
     * Shorten logger name for readability (e.g., o.e.r.booking.BookingService).
     */
    private String shortenLoggerName(String loggerName) {
        if (loggerName == null || loggerName.length() <= 40) {
            return loggerName;
        }
        
        String[] parts = loggerName.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i].charAt(0)).append(".");
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }
}
