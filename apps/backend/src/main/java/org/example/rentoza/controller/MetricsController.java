package org.example.rentoza.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.dto.WebVitalsMetricDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    /**
     * Receive Web Vitals metrics from frontend
     * In production, you would store these in a database or send to monitoring service
     */
    @PostMapping("/web-vitals")
    public ResponseEntity<Void> receiveWebVitals(@RequestBody List<WebVitalsMetricDTO> metrics) {
        metrics.forEach(metric -> {
            log.info("Web Vitals - {}: {} ({}) - URL: {}", 
                metric.getName(), 
                formatMetricValue(metric),
                metric.getRating(),
                metric.getUrl()
            );
            
            // TODO: Store in database for analytics
            // TODO: Or forward to APM service (Prometheus, Datadog, New Relic, etc.)
            // metricsService.storeWebVitals(metric);
        });
        
        return ResponseEntity.ok().build();
    }
    
    private String formatMetricValue(WebVitalsMetricDTO metric) {
        // CLS is unitless, others are in milliseconds
        if ("CLS".equals(metric.getName())) {
            return String.format("%.3f", metric.getValue());
        }
        return String.format("%.0fms", metric.getValue());
    }
}
