package org.example.rentoza.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Metrics tracking for missing static resources.
 * 
 * <p>Tracks when users request images/files that no longer exist on disk,
 * enabling:
 * <ul>
 *   <li>Identifying frequently accessed missing resources</li>
 *   <li>Detecting orphaned DB references</li>
 *   <li>Monitoring cleanup job effectiveness</li>
 *   <li>Alerting when missing resource rate spikes</li>
 * </ul>
 * 
 * <p><strong>Metrics Exposed:</strong>
 * <pre>
 * missing_resources_total{type="car-image",path="/car-images/47/photo.jpg"} 15
 * missing_resources_total{type="check-in-photo",path="/check-in-photos/123/exterior.jpg"} 3
 * missing_resources_total{type="user-avatar",path="/user-avatars/5/profile.jpg"} 1
 * </pre>
 * 
 * <p><strong>Alerting Rules (Prometheus):</strong>
 * <pre>
 * # Alert when missing resources exceed threshold
 * - alert: HighMissingResourceRate
 *   expr: rate(missing_resources_total[5m]) > 10
 *   for: 5m
 *   annotations:
 *     summary: High rate of missing resource requests
 *     description: "{{ $value }} missing resources/sec for {{ $labels.type }}"
 * </pre>
 * 
 * <p><strong>Grafana Dashboard Query:</strong>
 * <pre>
 * # Top 10 missing resources
 * topk(10, sum by (path) (missing_resources_total))
 * </pre>
 * 
 * @see StaticResourceExceptionHandler where metrics are recorded
 */
@Component
public class MissingResourceMetrics {

    private static final Logger log = LoggerFactory.getLogger(MissingResourceMetrics.class);

    private final MeterRegistry meterRegistry;

    public MissingResourceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("[MissingResourceMetrics] Initialized with registry: {}", meterRegistry.getClass().getSimpleName());
    }

    /**
     * Record a missing resource access attempt.
     * 
     * <p>Increments counter with resource type and path tags for granular tracking.
     * 
     * <p><strong>Example Usage:</strong>
     * <pre>
     * metrics.recordMissingResource("/car-images/47/photo.jpg");
     * metrics.recordMissingResource("/check-in-photos/123/exterior.jpg");
     * </pre>
     * 
     * @param resourcePath Full path of missing resource (e.g., "/car-images/47/photo.jpg")
     */
    public void recordMissingResource(String resourcePath) {
        String resourceType = extractResourceType(resourcePath);
        
        Counter.builder("missing_resources_total")
                .description("Count of missing static resource access attempts")
                .tag("type", resourceType)
                .register(meterRegistry)
                .increment();

        log.debug("[MissingResourceMetrics] Recorded missing resource: type={}, path={}", 
                resourceType, resourcePath);
    }

    /**
     * Extract resource type from path for metric tagging.
     * 
     * <p>Maps URL patterns to logical types:
     * <ul>
     *   <li>/car-images/** → "car-image"</li>
     *   <li>/check-in-photos/** → "check-in-photo"</li>
     *   <li>/user-avatars/** → "user-avatar"</li>
     *   <li>/documents/** → "document"</li>
     *   <li>Other → "unknown"</li>
     * </ul>
     * 
     * @param resourcePath Resource path to classify
     * @return Resource type string for metrics tagging
     */
    private String extractResourceType(String resourcePath) {
        if (resourcePath == null) {
            return "unknown";
        }
        
        String path = resourcePath.toLowerCase();
        
        if (path.startsWith("/car-images/")) {
            return "car-image";
        } else if (path.startsWith("/check-in-photos/")) {
            return "check-in-photo";
        } else if (path.startsWith("/user-avatars/")) {
            return "user-avatar";
        } else if (path.startsWith("/documents/")) {
            return "document";
        } else if (path.startsWith("/uploads/")) {
            return "legacy-upload";
        } else {
            return "unknown";
        }
    }

    /**
     * Truncate long paths for metric storage efficiency.
     * 
     * <p>Limits path length to prevent high cardinality metrics that
     * could impact Prometheus/Grafana performance.
     * 
     * <p>Truncation strategy:
     * <ul>
     *   <li>Paths ≤ 100 chars: Keep as-is</li>
     *   <li>Paths > 100 chars: Truncate to 97 chars + "..."</li>
     * </ul>
     * 
     * @param path Resource path to potentially truncate
     * @return Truncated path (max 100 chars)
     */
    private String truncatePath(String path) {
        if (path == null) {
            return "null";
        }
        
        // Limit path length to prevent high cardinality metrics
        if (path.length() > 100) {
            return path.substring(0, 97) + "...";
        }
        
        return path;
    }

    /**
     * Record a placeholder image served.
     * 
     * <p>Tracks when placeholder fallback is used instead of actual image.
     * Useful for monitoring placeholder usage rate and effectiveness.
     * 
     * @param originalPath Path of missing image that triggered placeholder
     */
    public void recordPlaceholderServed(String originalPath) {
        String resourceType = extractResourceType(originalPath);
        
        Counter.builder("placeholder_images_served_total")
                .description("Count of placeholder images served for missing resources")
                .tag("type", resourceType)
                .register(meterRegistry)
                .increment();

        log.debug("[MissingResourceMetrics] Placeholder served for: {}", originalPath);
    }
}
