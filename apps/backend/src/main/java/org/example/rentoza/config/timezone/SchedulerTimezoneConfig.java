package org.example.rentoza.config.timezone;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesException;
import java.util.TimeZone;

/**
 * Global scheduler timezone configuration for enterprise-grade timezone handling.
 * 
 * <h2>Purpose</h2>
 * <p>Ensures all scheduled tasks run in Serbian timezone, regardless of server location.
 * Also sets the JVM default timezone as a fallback safety net.
 * 
 * <h2>Configuration Hierarchy</h2>
 * <ol>
 *   <li><b>@Scheduled(zone = "...")</b> - Method-level override (highest priority)</li>
 *   <li><b>This configuration</b> - Sets JVM default timezone for fallback</li>
 *   <li><b>Server timezone</b> - Only used if above fail (should never happen)</li>
 * </ol>
 * 
 * <h2>Properties</h2>
 * <pre>
 * # application.properties
 * app.scheduler.timezone=Europe/Belgrade
 * app.scheduler.validate-timezone-on-startup=true
 * </pre>
 * 
 * <h2>Validation</h2>
 * <p>On startup, this configuration:
 * <ul>
 *   <li>Validates the configured timezone exists</li>
 *   <li>Sets JVM default timezone</li>
 *   <li>Logs current time in all relevant zones</li>
 *   <li>Warns if DST transition is approaching</li>
 * </ul>
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.0 - Timezone Standardization
 */
@Configuration
@ConfigurationProperties(prefix = "app.scheduler")
public class SchedulerTimezoneConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTimezoneConfig.class);

    /**
     * Target timezone for all schedulers.
     * Default: Europe/Belgrade (Serbia/Croatia/Bosnia)
     */
    private String timezone = SerbiaTimeZone.ZONE_ID_STRING;

    /**
     * Whether to validate timezone configuration on startup.
     */
    private boolean validateTimezoneOnStartup = true;

    /**
     * Days before DST transition to start warning in logs.
     */
    private int dstWarningDays = 7;

    // ==================== GETTERS & SETTERS ====================

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public boolean isValidateTimezoneOnStartup() {
        return validateTimezoneOnStartup;
    }

    public void setValidateTimezoneOnStartup(boolean validateTimezoneOnStartup) {
        this.validateTimezoneOnStartup = validateTimezoneOnStartup;
    }

    public int getDstWarningDays() {
        return dstWarningDays;
    }

    public void setDstWarningDays(int dstWarningDays) {
        this.dstWarningDays = dstWarningDays;
    }

    // ==================== INITIALIZATION ====================

    /**
     * Validate and configure timezone on application startup.
     */
    @PostConstruct
    public void initializeTimezone() {
        log.info("🕐 Initializing Scheduler Timezone Configuration");

        // Validate timezone
        ZoneId configuredZone;
        try {
            configuredZone = ZoneId.of(timezone);
        } catch (ZoneRulesException e) {
            log.error("❌ Invalid timezone configured: '{}'. Falling back to Europe/Belgrade", timezone);
            configuredZone = SerbiaTimeZone.ZONE_ID;
            timezone = SerbiaTimeZone.ZONE_ID_STRING;
        }

        // Set JVM default timezone as safety net
        TimeZone.setDefault(TimeZone.getTimeZone(configuredZone));
        log.info("   ✅ JVM default timezone set to: {}", timezone);

        if (validateTimezoneOnStartup) {
            performStartupValidation(configuredZone);
        }
    }

    /**
     * Perform detailed startup validation and logging.
     */
    private void performStartupValidation(ZoneId configuredZone) {
        ZonedDateTime nowSerbia = ZonedDateTime.now(configuredZone);
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));

        log.info("   📍 Scheduler timezone: {} ({})", 
                timezone, 
                SerbiaTimeZone.isDaylightSavingTime() ? "CEST - Summer Time" : "CET - Winter Time");
        log.info("   ⏰ Current time in {}:  {}", timezone, nowSerbia.format(SerbiaTimeZone.DATETIME_FORMAT));
        log.info("   ⏰ Current time in UTC: {}", nowUtc.format(SerbiaTimeZone.DATETIME_FORMAT));
        log.info("   📊 UTC offset: {}", SerbiaTimeZone.currentOffset());

        // Check for upcoming DST transition
        ZonedDateTime nextTransition = SerbiaTimeZone.nextDstTransition();
        if (nextTransition != null) {
            long daysUntilTransition = java.time.temporal.ChronoUnit.DAYS.between(
                    nowSerbia.toLocalDate(), 
                    nextTransition.toLocalDate()
            );

            if (daysUntilTransition <= dstWarningDays) {
                log.warn("   ⚠️  DST TRANSITION ALERT: Clock change in {} days on {}", 
                        daysUntilTransition, 
                        nextTransition.format(SerbiaTimeZone.DATE_FORMAT));
                log.warn("   ⚠️  Verify scheduled tasks handle this correctly!");
            } else {
                log.info("   📅 Next DST transition: {} ({} days away)", 
                        nextTransition.format(SerbiaTimeZone.DATE_FORMAT), 
                        daysUntilTransition);
            }
        }

        // Verify timezone consistency
        if (!configuredZone.equals(SerbiaTimeZone.ZONE_ID)) {
            log.warn("   ⚠️  Custom timezone '{}' differs from default Serbia timezone!", timezone);
            log.warn("   ⚠️  Ensure this is intentional for your deployment region.");
        }

        log.info("   ✅ Timezone configuration validated successfully");
    }

    // ==================== SCHEDULER CONFIGURATION ====================

    /**
     * Configure the scheduler with timezone-aware task executor.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // Note: Spring's @Scheduled uses the zone attribute for cron expressions.
        // This method is called for additional customization if needed.
        // The actual timezone is set in @Scheduled(zone = "...") on each method.
        
        log.debug("Scheduler task registrar configured with timezone: {}", timezone);
    }

    /**
     * Get the configured ZoneId for programmatic use.
     */
    public ZoneId getConfiguredZoneId() {
        try {
            return ZoneId.of(timezone);
        } catch (ZoneRulesException e) {
            return SerbiaTimeZone.ZONE_ID;
        }
    }

    /**
     * Convert a cron time to the actual execution time in configured timezone.
     * Useful for debugging and logging.
     * 
     * @param cronHour Hour from cron expression (0-23)
     * @param cronMinute Minute from cron expression (0-59)
     * @return Human-readable execution time
     */
    public String describeCronTime(int cronHour, int cronMinute) {
        ZonedDateTime serbiaTime = ZonedDateTime.now(SerbiaTimeZone.ZONE_ID)
                .withHour(cronHour)
                .withMinute(cronMinute)
                .withSecond(0);
        ZonedDateTime utcTime = serbiaTime.withZoneSameInstant(ZoneId.of("UTC"));

        return String.format("%02d:%02d %s (%02d:%02d UTC)", 
                cronHour, cronMinute, timezone,
                utcTime.getHour(), utcTime.getMinute());
    }
}
