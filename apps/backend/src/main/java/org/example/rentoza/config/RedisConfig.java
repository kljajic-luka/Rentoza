package org.example.rentoza.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for distributed rate limiting and caching.
 * 
 * CONDITIONAL ACTIVATION:
 * - Only activates when app.redis.enabled=true is configured
 * - In development, InMemoryRateLimitService is used by default
 * - In production, configure Redis for horizontal scaling
 * 
 * Production Requirements:
 * - Redis 6.0+ recommended
 * - Password authentication enabled (spring.data.redis.password)
 * - SSL/TLS for encrypted connections (spring.data.redis.ssl.enabled=true)
 * - Redis Sentinel or Cluster for high availability
 * 
 * Configuration Properties:
 * - app.redis.enabled=true: Enable Redis (required)
 * - spring.data.redis.host: Redis server hostname
 * - spring.data.redis.port: Redis server port (default: 6379)
 * - spring.data.redis.password: Authentication password
 * - spring.data.redis.timeout: Connection timeout
 * - spring.data.redis.lettuce.pool.*: Connection pool settings
 * 
 * @author Rentoza Platform Team
 * @since Phase 2.3 - Redis Rate Limiting
 */
@Configuration
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /**
     * Configure RedisTemplate for rate limiting operations.
     * 
     * Serialization Strategy:
     * - Keys: String serializer (rate_limit:ip:192.168.1.1:60)
     * - Values: String serializer (counter value as string)
     * 
     * This matches the Lua script used in RedisRateLimitService.
     * 
     * @param connectionFactory Redis connection factory (auto-configured by Spring Boot)
     * @return Configured RedisTemplate for string key-value operations
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializers for rate limiting keys and values
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        
        // Enable transaction support for atomic operations
        template.setEnableTransactionSupport(false); // We use Lua scripts instead
        
        template.afterPropertiesSet();
        
        log.info("✅ RedisTemplate configured for rate limiting");
        log.info("   - Connection Factory: {}", connectionFactory.getClass().getSimpleName());
        
        return template;
    }
}
