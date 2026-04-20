package org.example.rentoza.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.notification.channel.NotificationChannel;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * C1 FIX: Scheduled worker that processes the notification delivery outbox.
 *
 * Runs every 30 seconds to retry failed email/push deliveries.
 * Uses distributed locking to prevent duplicate processing in multi-instance deployments.
 * Provides metrics for delivery success/failure/dead-letter counts.
 */
@Service
@Slf4j
public class NotificationDeliveryRetryWorker {

    private final NotificationDeliveryOutboxRepository outboxRepository;
    private final NotificationRepository notificationRepository;
    private final List<NotificationChannel> notificationChannels;
    private final SchedulerIdempotencyService lockService;
    private final Counter deliverySuccess;
    private final Counter deliveryFailure;
    private final Counter deliveryDeadLetter;

    public NotificationDeliveryRetryWorker(
            NotificationDeliveryOutboxRepository outboxRepository,
            NotificationRepository notificationRepository,
            List<NotificationChannel> notificationChannels,
            SchedulerIdempotencyService lockService,
            MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.notificationRepository = notificationRepository;
        this.notificationChannels = notificationChannels;
        this.lockService = lockService;

        this.deliverySuccess = Counter.builder("notification.delivery.success")
                .description("Successful notification deliveries via outbox retry")
                .register(meterRegistry);
        this.deliveryFailure = Counter.builder("notification.delivery.failure")
                .description("Failed notification delivery attempts")
                .register(meterRegistry);
        this.deliveryDeadLetter = Counter.builder("notification.delivery.dead_letter")
                .description("Notifications moved to dead letter after max retries")
                .register(meterRegistry);
    }

    /**
     * Process pending outbox entries every 30 seconds.
     * Picks up entries where next_attempt_at <= now.
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void processOutbox() {
        if (!lockService.tryAcquireLock("notification.outbox.processor", Duration.ofSeconds(25))) {
            return;
        }

        try {
            List<NotificationDeliveryOutbox> pending = outboxRepository.findPendingForRetry(Instant.now());
            if (pending.isEmpty()) {
                return;
            }

            log.info("[Outbox] Processing {} pending delivery entries", pending.size());

            // Build channel lookup map
            Map<String, NotificationChannel> channelMap = notificationChannels.stream()
                    .collect(Collectors.toMap(NotificationChannel::getChannelName, Function.identity()));

            for (NotificationDeliveryOutbox entry : pending) {
                processEntry(entry, channelMap);
            }
        } finally {
            lockService.releaseLock("notification.outbox.processor");
        }
    }

    private void processEntry(NotificationDeliveryOutbox entry, Map<String, NotificationChannel> channelMap) {
        NotificationChannel channel = channelMap.get(entry.getChannelName());
        if (channel == null || !channel.isEnabled()) {
            log.warn("[Outbox] Channel '{}' not found or disabled for outbox entry {}", entry.getChannelName(), entry.getId());
            entry.markAttemptFailed("Channel not available: " + entry.getChannelName());
            outboxRepository.save(entry);
            return;
        }

        Notification notification = notificationRepository.findById(entry.getNotificationId()).orElse(null);
        if (notification == null) {
            log.warn("[Outbox] Notification {} not found for outbox entry {}", entry.getNotificationId(), entry.getId());
            entry.markAttemptFailed("Notification not found");
            outboxRepository.save(entry);
            return;
        }

        try {
            channel.send(notification);
            entry.markDelivered();
            outboxRepository.save(entry);
            deliverySuccess.increment();
            log.debug("[Outbox] Successfully delivered notification {} via {}", entry.getNotificationId(), entry.getChannelName());
        } catch (Exception e) {
            entry.markAttemptFailed(e.getMessage());
            outboxRepository.save(entry);

            if (entry.getStatus() == NotificationDeliveryOutbox.DeliveryStatus.DEAD_LETTER) {
                deliveryDeadLetter.increment();
                log.error("[Outbox] DEAD LETTER: Notification {} via {} failed after {} attempts: {}",
                        entry.getNotificationId(), entry.getChannelName(), entry.getAttemptCount(), e.getMessage());
            } else {
                deliveryFailure.increment();
                log.warn("[Outbox] Retry scheduled for notification {} via {} (attempt {}/{}, next: {}): {}",
                        entry.getNotificationId(), entry.getChannelName(),
                        entry.getAttemptCount(), entry.getMaxAttempts(),
                        entry.getNextAttemptAt(), e.getMessage());
            }
        }
    }
}
