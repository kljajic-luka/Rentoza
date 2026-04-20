package org.example.rentoza.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Post-processor that removes Redis-related beans when Redis is disabled.
 * 
 * <h2>Problem Solved</h2>
 * <p>Spring Boot's {@link RedisAutoConfiguration} creates beans like
 * {@code StringRedisTemplate} and {@code RedisConnectionFactory} by default.
 * Even with {@code @ConditionalOnProperty} on our configs, the auto-configuration
 * still creates these beans, which then try to connect to localhost:6379
 * causing {@code RedisConnectionFailureException}.
 * 
 * <h2>Solution</h2>
 * <p>This post-processor runs early in the bean lifecycle and removes
 * Redis-related bean definitions when {@code app.redis.enabled=false}.
 * 
 * <h2>Configuration</h2>
 * <pre>
 * # Disable Redis (removes all Redis beans)
 * app.redis.enabled=false
 * 
 * # Enable Redis (normal auto-configuration)
 * app.redis.enabled=true
 * </pre>
 * 
 * <h2>Implementation Note</h2>
 * <p>Implements {@link PriorityOrdered} with {@link Ordered#HIGHEST_PRECEDENCE}
 * to run before other post-processors that might depend on Redis beans.
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.1 - Redis Configuration Hardening
 */
@Component
public class RedisDisablingPostProcessor 
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(RedisDisablingPostProcessor.class);

    /**
     * Bean names to remove when Redis is disabled.
     * These are created by RedisAutoConfiguration.
     */
    private static final List<String> REDIS_BEAN_NAMES = Arrays.asList(
        "redisConnectionFactory",
        "lettuceConnectionFactory",
        "jedisConnectionFactory",
        "redisTemplate",
        "stringRedisTemplate",
        "redisCustomConversions"
    );

    private Environment environment;
    private boolean redisEnabled = true;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
        this.redisEnabled = environment.getProperty("app.redis.enabled", Boolean.class, true);
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (redisEnabled) {
            log.debug("[Redis] Redis is enabled, keeping auto-configured beans");
            return;
        }

        log.info("[Redis] Redis is DISABLED (app.redis.enabled=false) - removing auto-configured beans");

        int removed = 0;
        for (String beanName : REDIS_BEAN_NAMES) {
            if (registry.containsBeanDefinition(beanName)) {
                registry.removeBeanDefinition(beanName);
                log.debug("[Redis] Removed bean definition: {}", beanName);
                removed++;
            }
        }

        // Also remove any bean with "redis" in the name (case-insensitive)
        for (String beanName : registry.getBeanDefinitionNames()) {
            if (beanName.toLowerCase().contains("redis") && 
                !beanName.contains("Disabling") && 
                !beanName.contains("InMemory")) {
                try {
                    BeanDefinition bd = registry.getBeanDefinition(beanName);
                    String className = bd.getBeanClassName();
                    // Only remove Spring Data Redis beans, not our custom ones
                    if (className != null && className.startsWith("org.springframework.data.redis")) {
                        registry.removeBeanDefinition(beanName);
                        log.debug("[Redis] Removed Spring Data Redis bean: {}", beanName);
                        removed++;
                    }
                } catch (Exception e) {
                    log.trace("[Redis] Could not process bean {}: {}", beanName, e.getMessage());
                }
            }
        }

        if (removed > 0) {
            log.info("[Redis] Removed {} Redis bean definition(s) - using in-memory implementations", removed);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // No additional processing needed
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
