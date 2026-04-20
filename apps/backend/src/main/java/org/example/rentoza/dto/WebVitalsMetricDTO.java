package org.example.rentoza.dto;

import lombok.Data;

@Data
public class WebVitalsMetricDTO {
    private String name;
    private Double value;
    private String rating;
    private Double delta;
    private String id;
    private String navigationType;
    private Long timestamp;
    private String url;
    private String userAgent;
}
