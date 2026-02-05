package org.example.rentoza.config.logging;

import org.hibernate.engine.jdbc.internal.BasicFormatterImpl;

/**
 * Custom SQL formatter for Hibernate to improve readability in development logs.
 * 
 * Features:
 * - ANSI color codes for SQL keywords (SELECT, INSERT, UPDATE, DELETE, etc.)
 * - Proper indentation for multi-line queries
 * - Line breaks for better visual structure
 * - Uppercase keywords for consistency
 * 
 * This formatter is automatically applied when:
 * 1. spring.jpa.properties.hibernate.format_sql=true
 * 2. logging.level.org.hibernate.SQL=DEBUG
 * 
 * Only active in development profile for terminal color support.
 * 
 * @author Rentoza Development Team
 */
public class PrettySqlFormatter extends BasicFormatterImpl {

    // ANSI color codes for terminal output
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BLUE = "\u001B[34m";      // SELECT
    private static final String ANSI_GREEN = "\u001B[32m";     // INSERT
    private static final String ANSI_YELLOW = "\u001B[33m";    // UPDATE
    private static final String ANSI_RED = "\u001B[31m";       // DELETE
    private static final String ANSI_CYAN = "\u001B[36m";      // JOIN, WHERE, FROM
    private static final String ANSI_MAGENTA = "\u001B[35m";   // SET, VALUES
    private static final String ANSI_BOLD = "\u001B[1m";

    // Visual separators for SQL blocks
    private static final String SQL_SEPARATOR = "─".repeat(60);
    private static final String SQL_START_MARKER = "🧩 SQL QUERY";
    
    @Override
    public String format(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }

        // First, apply basic formatting (indentation, line breaks)
        String formatted = super.format(sql);

        // Apply color codes only in development
        if (isDevelopmentMode()) {
            formatted = applyColorCodes(formatted);
        }

        return formatted;
    }

    /**
     * Apply ANSI color codes to SQL keywords for better readability.
     */
    private String applyColorCodes(String sql) {
        // Main query types
        sql = sql.replaceAll("(?i)\\bselect\\b", ANSI_BLUE + ANSI_BOLD + "SELECT" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\binsert\\b", ANSI_GREEN + ANSI_BOLD + "INSERT" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bupdate\\b", ANSI_YELLOW + ANSI_BOLD + "UPDATE" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bdelete\\b", ANSI_RED + ANSI_BOLD + "DELETE" + ANSI_RESET);

        // Clauses
        sql = sql.replaceAll("(?i)\\bfrom\\b", ANSI_CYAN + "FROM" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bwhere\\b", ANSI_CYAN + "WHERE" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bjoin\\b", ANSI_CYAN + "JOIN" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\binner join\\b", ANSI_CYAN + "INNER JOIN" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bleft join\\b", ANSI_CYAN + "LEFT JOIN" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bright join\\b", ANSI_CYAN + "RIGHT JOIN" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bon\\b", ANSI_CYAN + "ON" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\band\\b", ANSI_CYAN + "AND" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bor\\b", ANSI_CYAN + "OR" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bgroup by\\b", ANSI_CYAN + "GROUP BY" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\border by\\b", ANSI_CYAN + "ORDER BY" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bhaving\\b", ANSI_CYAN + "HAVING" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\blimit\\b", ANSI_CYAN + "LIMIT" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\boffset\\b", ANSI_CYAN + "OFFSET" + ANSI_RESET);

        // INSERT/UPDATE specific
        sql = sql.replaceAll("(?i)\\binto\\b", ANSI_MAGENTA + "INTO" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bvalues\\b", ANSI_MAGENTA + "VALUES" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bset\\b", ANSI_MAGENTA + "SET" + ANSI_RESET);

        // Aggregates
        sql = sql.replaceAll("(?i)\\bcount\\(", ANSI_YELLOW + "COUNT" + ANSI_RESET + "(");
        sql = sql.replaceAll("(?i)\\bsum\\(", ANSI_YELLOW + "SUM" + ANSI_RESET + "(");
        sql = sql.replaceAll("(?i)\\bavg\\(", ANSI_YELLOW + "AVG" + ANSI_RESET + "(");
        sql = sql.replaceAll("(?i)\\bmax\\(", ANSI_YELLOW + "MAX" + ANSI_RESET + "(");
        sql = sql.replaceAll("(?i)\\bmin\\(", ANSI_YELLOW + "MIN" + ANSI_RESET + "(");

        // Operators
        sql = sql.replaceAll("(?i)\\bin\\b", ANSI_CYAN + "IN" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bnot in\\b", ANSI_CYAN + "NOT IN" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\blike\\b", ANSI_CYAN + "LIKE" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bnot\\b", ANSI_CYAN + "NOT" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bis null\\b", ANSI_CYAN + "IS NULL" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bis not null\\b", ANSI_CYAN + "IS NOT NULL" + ANSI_RESET);
        sql = sql.replaceAll("(?i)\\bbetween\\b", ANSI_CYAN + "BETWEEN" + ANSI_RESET);

        // AS keyword
        sql = sql.replaceAll("(?i)\\bas\\b", ANSI_CYAN + "AS" + ANSI_RESET);

        return sql;
    }

    /**
     * Check if running in development mode.
     * SQL coloring is only applied in development to avoid ANSI codes in production logs.
     */
    private boolean isDevelopmentMode() {
        String profile = System.getProperty("spring.profiles.active");
        return profile != null && profile.contains("dev");
    }

    /**
     * Format a SQL query with visual separators for better log readability.
     * This method can be called manually to wrap SQL logs with visual markers.
     * 
     * @param context The context (e.g., "BookingRepository.findById")
     * @param sql The SQL query
     * @return Formatted SQL with separators
     */
    public static String formatWithContext(String context, String sql) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(SQL_SEPARATOR).append("\n");
        sb.append(SQL_START_MARKER);
        if (context != null && !context.isEmpty()) {
            sb.append(" (").append(context).append(")");
        }
        sb.append("\n").append(SQL_SEPARATOR).append("\n");
        sb.append(sql);
        sb.append("\n").append(SQL_SEPARATOR).append("\n");
        return sb.toString();
    }
}
