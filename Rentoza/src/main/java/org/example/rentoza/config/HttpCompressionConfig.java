package org.example.rentoza.config;

import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * HTTP Compression Configuration for API responses.
 * 
 * <h2>Phase 3: API Optimization</h2>
 * <p>Enables GZIP compression for JSON responses to reduce bandwidth:
 * <ul>
 *   <li>Check-in status responses: ~70% size reduction</li>
 *   <li>Photo metadata lists: ~80% size reduction</li>
 *   <li>Mobile network bandwidth savings: Significant on 4G/LTE</li>
 * </ul>
 * 
 * <h3>Configuration</h3>
 * <ul>
 *   <li>Minimum size: 1KB (smaller responses not worth compressing)</li>
 *   <li>MIME types: JSON, text, JavaScript, CSS</li>
 *   <li>Excluded: Images, already-compressed formats</li>
 * </ul>
 * 
 * <h3>Alternative: Nginx/Load Balancer</h3>
 * <p>In production, gzip is typically handled by the reverse proxy (Nginx).
 * This configuration serves as a fallback and for development environments.
 * 
 * @see application.yml for declarative configuration option
 */
@Configuration
public class HttpCompressionConfig {

    /**
     * Customize embedded Tomcat server compression settings.
     * 
     * <p>This bean programmatically enables compression with fine-grained control.
     * For most deployments, the application.yml settings are preferred.
     */
    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> compressionCustomizer() {
        return factory -> {
            Compression compression = new Compression();
            
            // Enable compression
            compression.setEnabled(true);
            
            // Minimum response size to compress (1KB)
            // Smaller responses may not benefit from compression overhead
            compression.setMinResponseSize(DataSize.ofKilobytes(1));
            
            // MIME types to compress
            compression.setMimeTypes(new String[] {
                // JSON (API responses)
                "application/json",
                "application/json;charset=UTF-8",
                
                // Text formats
                "text/html",
                "text/plain",
                "text/xml",
                "text/css",
                
                // JavaScript
                "application/javascript",
                "text/javascript",
                
                // XML
                "application/xml",
                
                // GraphQL (if used)
                "application/graphql+json"
            });
            
            // User-Agent patterns to exclude (broken gzip support)
            // Modern browsers all support gzip, but some old proxies/bots don't
            compression.setExcludedUserAgents(new String[] {
                ".*MSIE 6.*",           // Internet Explorer 6
                ".*curl/7\\.[0-4].*"    // Old curl versions
            });
            
            factory.setCompression(compression);
        };
    }
}
