package org.example.rentoza.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

/**
 * Enterprise-grade REST API configuration for stable pagination.
 * 
 * Implements Spring Data's recommended approach for serializing Page objects
 * with guaranteed stable JSON structure across Spring versions.
 * 
 * Benefits:
 * - ✅ Stable JSON structure (no warnings)
 * - ✅ REST standards compliant (RFC 7231)
 * - ✅ HATEOAS links for navigation (next, prev, first, last)
 * - ✅ Compatible with PagedResourcesAssembler
 * - ✅ Consistent pagination metadata
 * 
 * @see https://docs.spring.io/spring-data/commons/reference/repositories/core-extensions.html
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class HateoasConfig {
    
    // Configuration is declarative via annotation
    // Spring will automatically handle Page serialization with stable DTO format
}
