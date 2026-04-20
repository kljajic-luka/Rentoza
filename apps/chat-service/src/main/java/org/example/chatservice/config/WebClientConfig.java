package org.example.chatservice.config;

import org.example.chatservice.security.InternalServiceJwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(WebClientConfig.class);

    @Value("${backend.api.base-url}")
    private String backendBaseUrl;

    private final InternalServiceJwtUtil internalServiceJwtUtil;

    public WebClientConfig(InternalServiceJwtUtil internalServiceJwtUtil) {
        this.internalServiceJwtUtil = internalServiceJwtUtil;
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        logger.info("Configuring WebClient with base URL: {}", backendBaseUrl);
        
        return builder
                .baseUrl(backendBaseUrl)
                .filter((request, next) -> {
                    // Generate a fresh internal service token for each request
                    String token = internalServiceJwtUtil.generateServiceToken();
                    
                    // Add the token to the X-Internal-Service-Token header
                    var modifiedRequest = org.springframework.web.reactive.function.client.ClientRequest
                            .from(request)
                            .header("X-Internal-Service-Token", token)
                            .build();
                    
                    logger.debug("🔐 Added internal service token to request: {} {}", 
                            request.method(), request.url());
                    
                    return next.exchange(modifiedRequest);
                })
                .build();
    }
}
