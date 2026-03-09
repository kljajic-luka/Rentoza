package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(name = "app.checkin.photo-recovery.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CheckInPhotoUploadRecoveryScheduler {

    private static final String LOCK_KEY = "checkin-photo-upload-recovery";

    private final CheckInPhotoService photoService;
    private final SchedulerIdempotencyService schedulerIdempotencyService;

    @Value("${app.checkin.photo-recovery.stale-minutes:15}")
    private int staleMinutes;

    @Scheduled(cron = "${app.checkin.scheduler.photo-upload-recovery-cron:0 */10 * * * *}", zone = "Europe/Belgrade")
    public void reconcileStalePhotoUploads() {
        if (!schedulerIdempotencyService.tryAcquireLock(LOCK_KEY, Duration.ofMinutes(9))) {
            log.debug("[CheckIn] Skipping duplicate photo upload reconciliation run");
            return;
        }

        Instant staleBefore = Instant.now().minus(staleMinutes, ChronoUnit.MINUTES);
        CheckInPhotoService.UploadReconciliationResult result = photoService.reconcileStaleUploads(staleBefore);

        if (result.finalized() > 0 || result.terminalFailures() > 0) {
            log.warn("[CheckIn] Photo upload reconciliation processed stale uploads: finalized={}, terminalFailures={}",
                    result.finalized(), result.terminalFailures());
        } else {
            log.debug("[CheckIn] No stale photo uploads required reconciliation");
        }
    }
}