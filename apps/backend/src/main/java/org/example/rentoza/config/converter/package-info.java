/**
 * JPA AttributeConverters for PostgreSQL native ENUM types.
 * 
 * <h2>Why Converters?</h2>
 * <p>PostgreSQL native ENUMs are strict types that don't accept VARCHAR comparisons.
 * When Hibernate generates queries like {@code WHERE status = ?} and binds a String,
 * PostgreSQL throws "operator does not exist: booking_status = character varying".
 * 
 * <p>These converters solve this by:
 * <ul>
 *   <li>Converting Java enum → String for INSERT/UPDATE</li>
 *   <li>Converting String → Java enum for SELECT</li>
 *   <li>Letting PostgreSQL auto-cast String to native ENUM</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <p>All converters use {@code @Converter(autoApply = true)} so they're automatically
 * applied to ALL entity fields of that enum type. No {@code @Convert} annotation needed.
 * 
 * <h2>Important: Remove @Enumerated</h2>
 * <p>When using converters, you may need to remove {@code @Enumerated(EnumType.STRING)}
 * from entity fields to avoid conflicts. The converter handles the conversion.
 * 
 * <h2>Supported Enums</h2>
 * <ul>
 *   <li>{@link BookingStatusConverter} - booking lifecycle states</li>
 *   <li>{@link AuthProviderConverter} - LOCAL, GOOGLE auth methods</li>
 *   <li>{@link RoleConverter} - USER, ADMIN, MODERATOR roles</li>
 *   <li>{@link RegistrationStatusConverter} - registration flow states</li>
 *   <li>{@link ApprovalStatusConverter} - car approval workflow</li>
 *   <li>{@link ListingStatusConverter} - car listing states</li>
 *   <li>{@link OwnerTypeConverter} - INDIVIDUAL, LEGAL_ENTITY</li>
 *   <li>{@link SagaStatusConverter} - checkout saga states</li>
 *   <li>{@link RenterDocumentTypeConverter} - document categories</li>
 *   <li>{@link ReviewDirectionConverter} - review direction (FROM_USER, TO_USER)</li>
 *   <li>{@link CancelledByConverter} - cancellation initiators</li>
 * </ul>
 * 
 * @see <a href="https://hibernate.org/orm/documentation/6.0/">Hibernate 6 Type System</a>
 * @see <a href="https://www.postgresql.org/docs/current/datatype-enum.html">PostgreSQL ENUM Types</a>
 */
package org.example.rentoza.config.converter;
