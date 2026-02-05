package org.example.rentoza.config.logging;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Hibernate StatementInspector for enhanced SQL logging in development.
 * 
 * Features:
 * - Tracks SQL execution count per session/request
 * - Provides context-aware logging with operation markers
 * - Visual separators for better log readability
 * - Performance timing hints (when Hibernate statistics are enabled)
 * 
 * Active only when:
 * - spring.profiles.active=dev
 * - logging.level.org.hibernate.SQL=DEBUG
 * 
 * @author Rentoza Development Team
 */
@Slf4j
@Component
public class SqlLoggingInspector implements StatementInspector {

    private static final String SQL_SEPARATOR = "═".repeat(80);
    private static final String SQL_SUB_SEPARATOR = "─".repeat(80);
    
    // Thread-local counter for tracking SQL queries per request
    private static final ThreadLocal<AtomicLong> queryCounter = 
        ThreadLocal.withInitial(() -> new AtomicLong(0));
    
    // Cache for repository/service names to avoid repeated reflection
    private final ConcurrentHashMap<String, String> contextCache = new ConcurrentHashMap<>();

    @Override
    public String inspect(String sql) {
        // Only enhance logging in development mode
        if (!isDevelopmentMode()) {
            return sql;
        }

        // Increment query counter for this thread/request
        long queryNum = queryCounter.get().incrementAndGet();

        // Determine query type for better logging
        String queryType = determineQueryType(sql);
        
        // Log with visual structure
        if (log.isDebugEnabled()) {
            logStructuredSql(sql, queryType, queryNum);
        }

        return sql;
    }

    /**
     * Log SQL with structured, visually appealing format.
     */
    private void logStructuredSql(String sql, String queryType, long queryNum) {
        StringBuilder logMessage = new StringBuilder();
        
        // Header with query number and type
        logMessage.append("\n").append(SQL_SEPARATOR).append("\n");
        logMessage.append(String.format("🔍 Hibernate SQL #%d | Type: %s", queryNum, queryType));
        logMessage.append("\n").append(SQL_SUB_SEPARATOR).append("\n");
        
        // Formatted SQL (will be colored by PrettySqlFormatter if enabled)
        logMessage.append(sql);
        
        logMessage.append("\n").append(SQL_SEPARATOR);
        
        log.debug(logMessage.toString());
    }

    /**
     * Determine the type of SQL query for better categorization.
     */
    private String determineQueryType(String sql) {
        String upperSql = sql.trim().toUpperCase();
        
        if (upperSql.startsWith("SELECT")) {
            if (upperSql.contains("COUNT(")) {
                return "📊 COUNT QUERY";
            } else if (upperSql.contains("JOIN")) {
                return "🔗 JOIN QUERY";
            }
            return "📖 SELECT";
        } else if (upperSql.startsWith("INSERT")) {
            return "➕ INSERT";
        } else if (upperSql.startsWith("UPDATE")) {
            return "✏️ UPDATE";
        } else if (upperSql.startsWith("DELETE")) {
            return "🗑️ DELETE";
        } else if (upperSql.startsWith("CALL")) {
            return "⚙️ STORED PROCEDURE";
        }
        
        return "❓ OTHER";
    }

    /**
     * Check if running in development mode.
     */
    private boolean isDevelopmentMode() {
        String profile = System.getProperty("spring.profiles.active");
        return profile != null && profile.contains("dev");
    }

    /**
     * Reset the query counter for the current thread.
     * Call this at the start of each request via a filter or interceptor.
     */
    public static void resetQueryCounter() {
        queryCounter.get().set(0);
    }

    /**
     * Get the current query count for this thread/request.
     */
    public static long getQueryCount() {
        return queryCounter.get().get();
    }

    /**
     * Extract context information from stack trace (expensive, use sparingly).
     */
    private String extractContext() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        // Look for repository or service class in stack trace
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            
            // Check if it's a repository or service
            if (className.contains("Repository") || className.contains("Service")) {
                // Cache the result
                return contextCache.computeIfAbsent(className, k -> {
                    String simpleName = k.substring(k.lastIndexOf('.') + 1);
                    String methodName = element.getMethodName();
                    return simpleName + "." + methodName;
                });
            }
        }
        
        return "Unknown Context";
    }
}
