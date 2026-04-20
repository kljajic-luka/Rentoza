package org.example.rentoza.config;

import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.type.descriptor.converter.spi.BasicValueConverter;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.ObjectJdbcType;

import java.sql.Types;

/**
 * PostgreSQL-specific enum handling for Hibernate 6.x with Supabase.
 * 
 * <p><b>Problem:</b> PostgreSQL native ENUMs are strict types. When Hibernate sends
 * {@code WHERE status = 'ACTIVE'} (VARCHAR), PostgreSQL rejects it with:
 * "operator does not exist: booking_status = character varying"
 * 
 * <p><b>Solution:</b> Use VARCHAR columns in PostgreSQL instead of native ENUMs,
 * with CHECK constraints for validation. This is the cloud-native best practice
 * used by Supabase, CockroachDB, and other distributed PostgreSQL systems.
 * 
 * <p><b>Migration Required:</b> If your schema uses native ENUMs, convert them:
 * <pre>
 * ALTER TABLE bookings 
 *   ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
 * </pre>
 * 
 * <p><b>Alternative (No Migration):</b> If you must keep native ENUMs, add explicit
 * casts in JPQL/native queries:
 * <pre>
 * &#64;Query("SELECT b FROM Booking b WHERE CAST(b.status AS text) = :status")
 * </pre>
 * 
 * @see <a href="https://www.postgresql.org/docs/current/datatype-enum.html">PostgreSQL ENUM Types</a>
 * @see <a href="https://supabase.com/docs/guides/database/postgres/enums">Supabase Enum Guide</a>
 */
public class PostgreSQLEnumType {
    
    /**
     * SQL type constant for PostgreSQL enum (OTHER type in JDBC).
     * Use this when setting parameters in native queries.
     */
    public static final int SQL_TYPE = Types.OTHER;
    
    private PostgreSQLEnumType() {
        // Utility class
    }
}
