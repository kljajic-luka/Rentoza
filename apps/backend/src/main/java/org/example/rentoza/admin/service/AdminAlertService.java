package org.example.rentoza.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.payment.PayoutLedger;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Servis za slanje admin alertova za kriticne operativne dogadjaje.
 *
 * <p>Salje notifikacije svim korisnicima sa ulogom {@link Role#ADMIN}
 * koristeci postojeci {@link NotificationService} za email kanal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAlertService {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Async
    public void alertVehicleNotReturned(Booking booking) {
        String message = String.format(
                "[HITNO] Vozilo nije vraceno — Rezervacija #%d, zakupac: %s %s. "
                + "Kasnjenje vise od 24h od planiranog vracanja (%s).",
                booking.getId(),
                booking.getRenter().getFirstName(),
                booking.getRenter().getLastName(),
                booking.getScheduledReturnTime());
        sendToAllAdmins(NotificationType.ADMIN_ALERT_VEHICLE_NOT_RETURNED, message,
                String.valueOf(booking.getId()));
    }

    @Async
    public void alertDepositCaptureFailed(Booking booking) {
        String message = String.format(
                "[ALERT] Depozit capture nije uspeo — Rezervacija #%d, zakupac ID: %d. "
                + "Potrebna manuelna provera.",
                booking.getId(),
                booking.getRenter().getId());
        sendToAllAdmins(NotificationType.ADMIN_ALERT_DEPOSIT_CAPTURE_FAILED, message,
                String.valueOf(booking.getId()));
    }

    @Async
    public void alertDisputeFiled(DamageClaim claim) {
        String message = String.format(
                "[ALERT] Novi dispute podnet — Claim #%d za rezervaciju #%d, iznos: %s RSD. "
                + "Tip: %s, faza: %s.",
                claim.getId(),
                claim.getBooking().getId(),
                claim.getClaimedAmount(),
                claim.getDisputeType(),
                claim.getDisputeStage());
        sendToAllAdmins(NotificationType.ADMIN_ALERT_DISPUTE_FILED, message,
                String.valueOf(claim.getId()));
    }

    @Async
    public void alertDisputeSlaBreached(DamageClaim claim) {
        String message = String.format(
                "[HITNO] Dispute SLA breach — Claim #%d za rezervaciju #%d je stariji od 48h "
                + "bez admin akcije. Status: %s, iznos: %s RSD.",
                claim.getId(),
                claim.getBooking().getId(),
                claim.getStatus(),
                claim.getClaimedAmount());
        sendToAllAdmins(NotificationType.ADMIN_ALERT_DISPUTE_SLA_BREACH, message,
                String.valueOf(claim.getId()));
    }

    @Async
    public void alertPayoutFailed(PayoutLedger payout) {
        String message = String.format(
                "[ALERT] Payout eskaliran na MANUAL_REVIEW — Payout #%d za rezervaciju #%d, "
                + "iznos: %s RSD. Greska: %s.",
                payout.getId(),
                payout.getBookingId(),
                payout.getHostPayoutAmount(),
                payout.getLastError() != null ? payout.getLastError() : "nepoznata");
        sendToAllAdmins(NotificationType.ADMIN_ALERT_PAYOUT_FAILED, message,
                String.valueOf(payout.getId()));
    }

    private void sendToAllAdmins(NotificationType type, String message, String relatedEntityId) {
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        if (admins.isEmpty()) {
            log.warn("[AdminAlert] Nema registrovanih ADMIN korisnika za alert: {}", type);
            return;
        }
        for (User admin : admins) {
            try {
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(admin.getId())
                        .type(type)
                        .message(message)
                        .relatedEntityId(relatedEntityId)
                        .build());
            } catch (Exception e) {
                log.error("[AdminAlert] Slanje {} za admin {} neuspesno: {}",
                        type, admin.getId(), e.getMessage());
            }
        }
        log.info("[AdminAlert] {} poslat {} admin korisnicima", type, admins.size());
    }
}
