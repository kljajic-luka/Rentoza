package org.example.rentoza.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Production-ready Redis connection configuration with:
 * - Sentinel/Cluster support for high availability
 * - Auto-reconnect with exponential backoff
 * - Connection pooling with health checks
 * - Topology refresh for cluster awareness
 * 
 * <h2>Connection Modes (Priority Order)</h2>
 * <ol>
 *   <li><b>Sentinel</b> - If spring.data.redis.sentinel.master is set</li>
 *   <li><b>Cluster</b> - If spring.data.redis.cluster.nodes is set</li>
 *   <li><b>Standalone</b> - Default fallback to single Redis instance</li>
 * </ol>
 * 
 * <h2>Resilience Features</h2>
 * <ul>
 *   <li>Auto-reconnect: Automatically reconnects after Redis restart</li>
 *   <li>Connection validation: Tests connections before use</li>
 *   <li>Topology refresh: Cluster-aware for node changes</li>
 *   <li>Socket keep-alive: Prevents connection drops</li>
 * </ul>
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.0 - Production Redis Hardening
 */
@Configuration
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionConfig.class);

    // ========== STANDALONE CONFIGURATION ==========
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    // ========== SENTINEL CONFIGURATION ==========
    @Value("${spring.data.redis.sentinel.master:}")
    private String sentinelMaster;

    @Value("${spring.data.redis.sentinel.nodes:}")
    private String sentinelNodes;

    @Value("${spring.data.redis.sentinel.password:}")
    private String sentinelPassword;

    // ========== CLUSTER CONFIGURATION ==========
    @Value("${spring.data.redis.cluster.nodes:}")
    private String clusterNodes;

    @Value("${spring.data.redis.cluster.max-redirects:3}")
    private int clusterMaxRedirects;

    // ========== CONNECTION SETTINGS ==========
    @Value("${spring.data.redis.timeout:2000ms}")
    private Duration commandTimeout;

    @Value("${spring.data.redis.connect-timeout:2000ms}")
    private Duration connectTimeout;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    // ========== POOL SETTINGS ==========
    @Value("${spring.data.redis.lettuce.pool.max-active:16}")
    private int poolMaxActive;

    @Value("${spring.data.redis.lettuce.pool.max-idle:8}")
    private int poolMaxIdle;

    @Value("${spring.data.redis.lettuce.pool.min-idle:2}")
    private int poolMinIdle;

    @Value("${spring.data.redis.lettuce.pool.max-wait:1000ms}")
    private Duration poolMaxWait;

    /**
     * Shared ClientResources for efficient resource management.
     * Reused across all connections to minimize thread/memory overhead.
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(4)
                .computationThreadPoolSize(4)
                .build();
    }

    /**
     * Create appropriate RedisConnectionFactory based on configuration.
     * Priority: Sentinel > Cluster > Standalone
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(ClientResources clientResources) {
        LettuceClientConfiguration clientConfig = buildClientConfiguration(clientResources);

        // Priority 1: Sentinel mode
        if (StringUtils.hasText(sentinelMaster) && StringUtils.hasText(sentinelNodes)) {
            log.info("🔴 Configuring Redis SENTINEL mode");
            return createSentinelConnectionFactory(clientConfig);
        }

        // Priority 2: Cluster mode
        if (StringUtils.hasText(clusterNodes)) {
            log.info("🔴 Configuring Redis CLUSTER mode");
            return createClusterConnectionFactory(clientResources);
        }

        // Priority 3: Standalone mode
        log.info("🔴 Configuring Redis STANDALONE mode");
        return createStandaloneConnectionFactory(clientConfig);
    }

    /**
     * Build Lettuce client configuration with resilience features.
     */
    @SuppressWarnings("unchecked")
    private LettuceClientConfiguration buildClientConfiguration(ClientResources clientResources) {
        // Use raw type and suppress warning - this is safe as pool config is used with Lettuce connections
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(poolMaxActive);
        poolConfig.setMaxIdle(poolMaxIdle);
        poolConfig.setMinIdle(poolMinIdle);
        poolConfig.setMaxWait(poolMaxWait);
        poolConfig.setTestOnBorrow(true);       // Validate before use
        poolConfig.setTestOnReturn(true);       // Validate on return
        poolConfig.setTestWhileIdle(true);      // Background validation
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        poolConfig.setBlockWhenExhausted(true);

        // Client options with auto-reconnect
        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)                                    // Auto-reconnect on connection loss
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(connectTimeout)
                        .keepAlive(true)                               // TCP keep-alive
                        .tcpNoDelay(true)                              // Disable Nagle's algorithm
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .publishOnScheduler(true)                              // Async command publishing
                .build();

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder()
                        .commandTimeout(commandTimeout)
                        .clientResources(clientResources)
                        .clientOptions(clientOptions)
                        .poolConfig(poolConfig);

        if (sslEnabled) {
            builder.useSsl();
            log.info("   ✅ SSL/TLS enabled for Redis connections");
        }

        return builder.build();
    }

    /**
     * Create Sentinel-based connection factory for high availability.
     */
    private LettuceConnectionFactory createSentinelConnectionFactory(LettuceClientConfiguration clientConfig) {
        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration();
        sentinelConfig.setMaster(sentinelMaster);

        // Parse sentinel nodes: "host1:port1,host2:port2,host3:port3"
        Arrays.stream(sentinelNodes.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(node -> {
                    String[] parts = node.split(":");
                    if (parts.length == 2) {
                        sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
                        log.info("   ✅ Added Sentinel node: {}:{}", parts[0], parts[1]);
                    }
                });

        // Set passwords
        if (StringUtils.hasText(redisPassword)) {
            sentinelConfig.setPassword(RedisPassword.of(redisPassword));
        }
        if (StringUtils.hasText(sentinelPassword)) {
            sentinelConfig.setSentinelPassword(RedisPassword.of(sentinelPassword));
        }

        sentinelConfig.setDatabase(redisDatabase);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfig, clientConfig);
        factory.setValidateConnection(true);
        factory.setShareNativeConnection(false);  // Each operation gets fresh connection

        log.info("   ✅ Sentinel master: {}", sentinelMaster);
        log.info("   ✅ Database: {}", redisDatabase);

        return factory;
    }

    /**
     * Create Cluster-based connection factory for sharded Redis.
     */
    private LettuceConnectionFactory createClusterConnectionFactory(ClientResources clientResources) {
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();
        clusterConfig.setMaxRedirects(clusterMaxRedirects);

        // Parse cluster nodes: "host1:port1,host2:port2,host3:port3"
        Arrays.stream(clusterNodes.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(node -> {
                    String[] parts = node.split(":");
                    if (parts.length == 2) {
                        clusterConfig.addClusterNode(new RedisNode(parts[0], Integer.parseInt(parts[1])));
                        log.info("   ✅ Added Cluster node: {}:{}", parts[0], parts[1]);
                    }
                });

        if (StringUtils.hasText(redisPassword)) {
            clusterConfig.setPassword(RedisPassword.of(redisPassword));
        }

        // Cluster-specific client options with topology refresh
        ClusterTopologyRefreshOptions topologyRefresh = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofSeconds(30))          // Refresh cluster topology
                .enableAllAdaptiveRefreshTriggers()                      // Refresh on errors
                .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(30))
                .build();

        ClusterClientOptions clusterClientOptions = ClusterClientOptions.builder()
                .autoReconnect(true)
                .topologyRefreshOptions(topologyRefresh)
                .validateClusterNodeMembership(false)  // Allow connections to non-members
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(connectTimeout)
                        .keepAlive(true)
                        .tcpNoDelay(true)
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .build();

        // Use raw type and suppress warning - this is safe as pool config is used with Lettuce connections
        @SuppressWarnings("rawtypes")
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(poolMaxActive);
        poolConfig.setMaxIdle(poolMaxIdle);
        poolConfig.setMinIdle(poolMinIdle);
        poolConfig.setMaxWait(poolMaxWait);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        @SuppressWarnings("unchecked")
        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder()
                        .commandTimeout(commandTimeout)
                        .clientResources(clientResources)
                        .clientOptions(clusterClientOptions)
                        .poolConfig(poolConfig);

        if (sslEnabled) {
            builder.useSsl();
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(clusterConfig, builder.build());
        factory.setValidateConnection(true);
        factory.setShareNativeConnection(false);

        log.info("   ✅ Max redirects: {}", clusterMaxRedirects);

        return factory;
    }

    /**
     * Create Standalone connection factory for single Redis instance.
     */
    private LettuceConnectionFactory createStandaloneConnectionFactory(LettuceClientConfiguration clientConfig) {
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration();
        standaloneConfig.setHostName(redisHost);
        standaloneConfig.setPort(redisPort);
        standaloneConfig.setDatabase(redisDatabase);

        if (StringUtils.hasText(redisPassword)) {
            standaloneConfig.setPassword(RedisPassword.of(redisPassword));
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.setValidateConnection(true);
        factory.setShareNativeConnection(false);

        log.info("   ✅ Host: {}:{}", redisHost, redisPort);
        log.info("   ✅ Database: {}", redisDatabase);
        log.info("   ⚠️ WARNING: Single instance mode - no HA. Use Sentinel/Cluster for production.");

        return factory;
    }

    /**
     * Customizer for additional Lettuce client configuration.
     * Applied to auto-configured LettuceConnectionFactory if present.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
        return builder -> {
            builder.commandTimeout(commandTimeout);
            
            // Configure client options with auto-reconnect
            ClientOptions options = ClientOptions.builder()
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(SocketOptions.builder()
                            .connectTimeout(connectTimeout)
                            .keepAlive(true)
                            .build())
                    .build();
            
            builder.clientOptions(options);
            
            log.info("✅ Lettuce client customizer applied: auto-reconnect enabled");
        };
    }
}
