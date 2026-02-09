package org.example.rentoza.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Disables Redis auto-configurations when app.redis.enabled=false.
 *
 * <p>Spring Boot evaluates auto-configurations early, so we set
 * {@code spring.autoconfigure.exclude} dynamically based on the flag.</p>
 */
public class RedisAutoConfigurationDisabler implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RedisAutoConfigurationDisabler.class);

    private static final List<String> REDIS_AUTOCONFIGS = Arrays.asList(
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean redisEnabled = environment.getProperty("app.redis.enabled", Boolean.class, true);
        if (redisEnabled) {
            return;
        }

        String existing = environment.getProperty("spring.autoconfigure.exclude", "");
        List<String> excludes = new ArrayList<>();
        if (StringUtils.hasText(existing)) {
            excludes.addAll(Arrays.asList(existing.split(",")));
        }
        for (String cfg : REDIS_AUTOCONFIGS) {
            if (!excludes.contains(cfg)) {
                excludes.add(cfg);
            }
        }
        String updated = String.join(",", excludes);
        environment.getSystemProperties().put("spring.autoconfigure.exclude", updated);
        log.info("[Redis] Disabled Redis auto-configurations (app.redis.enabled=false)");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
