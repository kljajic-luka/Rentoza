package org.example.rentoza.booking.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.RentalAgreementService;
import org.example.rentoza.booking.checkin.dto.CheckInAttestationResponseDTO;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.car.Car;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckInAttestationServiceTest {

    @Mock private CheckInAttestationRepository attestationRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CheckInPhotoRepository checkInPhotoRepository;
    @Mock private GuestCheckInPhotoRepository guestCheckInPhotoRepository;
    @Mock private CheckInEventRepository checkInEventRepository;
    @Mock private CheckInEventService checkInEventService;
    @Mock private RentalAgreementService rentalAgreementService;
    @Mock private SupabaseStorageService storageService;
    @Mock private PhotoUrlService photoUrlService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private CheckInAttestationService service;

    @BeforeEach
    void setUp() {
        service = new CheckInAttestationService(
                attestationRepository,
                bookingRepository,
                checkInPhotoRepository,
                guestCheckInPhotoRepository,
                checkInEventRepository,
                checkInEventService,
                rentalAgreementService,
                storageService,
                photoUrlService,
                new ObjectMapper(),
                applicationEventPublisher
        );
    }

    @Test
    void shouldReturnExistingAttestationWithoutRegeneration() throws Exception {
        Booking booking = booking();
        CheckInAttestation existing = CheckInAttestation.builder()
                .id(5L)
                .booking(booking)
                .checkInSessionId("session-1")
                .payloadHash("abc")
                .payloadJson("{}")
                .artifactStorageKey("attestations/bookings/1/session-1/a.html")
                .build();

        when(attestationRepository.findByCheckInSessionId("session-1")).thenReturn(Optional.of(existing));

        CheckInAttestation result = service.generateTripStartAttestation(booking, 99L);

        assertThat(result.getId()).isEqualTo(5L);
        verify(storageService, never()).uploadCheckInAttestationHtml(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldReturnSignedAttestationAndAuditAccess() {
        Booking booking = booking();
        CheckInAttestation existing = CheckInAttestation.builder()
                .id(6L)
                .booking(booking)
                .checkInSessionId("session-1")
                .payloadHash("hash-1")
                .payloadJson("{}")
                .artifactStorageKey("attestations/bookings/1/session-1/a.html")
                .createdAt(Instant.now())
                .build();

        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
        when(attestationRepository.findByBookingIdAndCheckInSessionId(1L, "session-1")).thenReturn(Optional.of(existing));
        when(photoUrlService.generateSignedUrl(anyString(), anyString(), anyLong())).thenReturn("https://signed.example/attestation");

        CheckInAttestationResponseDTO response = service.getAttestationForBooking(1L, 11L);

        assertThat(response.artifactUrl()).isEqualTo("https://signed.example/attestation");
        verify(checkInEventService).recordEvent(eq(booking), eq("session-1"), eq(CheckInEventType.CHECKIN_ATTESTATION_ACCESSED), eq(11L), eq(CheckInActorRole.HOST), anyMap());
    }

    @Test
    void shouldPublishAttestationRequestEvent() {
        service.requestTripStartAttestation(1L, "session-1", 99L);

        verify(applicationEventPublisher).publishEvent(new CheckInAttestationRequestedEvent(1L, "session-1", 99L));
    }

    @Test
    void shouldSwallowAsyncGenerationFailuresAndKeepThemObservable() throws Exception {
        Booking booking = booking();

        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
        when(attestationRepository.findByCheckInSessionId("session-1")).thenReturn(Optional.empty());
        when(checkInPhotoRepository.findByCheckInSessionId("session-1")).thenReturn(List.of());
        when(guestCheckInPhotoRepository.findByCheckInSessionId("session-1")).thenReturn(List.of());
        when(checkInEventRepository.findByCheckInSessionIdAndEventType(anyString(), any())).thenReturn(List.of());
        when(checkInEventRepository.findByCheckInSessionIdOrderByEventTimestampAsc("session-1")).thenReturn(List.of());

        assertThatCode(() -> service.handleTripStartAttestationRequested(
                new CheckInAttestationRequestedEvent(1L, "session-1", 77L)))
                .doesNotThrowAnyException();

        verify(attestationRepository).findByCheckInSessionId("session-1");
        verify(attestationRepository, never()).save(any(CheckInAttestation.class));
    }

    private Booking booking() {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setCheckInSessionId("session-1");
        booking.setStartTime(LocalDateTime.now().plusHours(1));
        booking.setEndTime(LocalDateTime.now().plusHours(5));
        booking.setTripStartedAt(Instant.now());

        Car car = new Car();
        car.setId(101L);
        User host = new User();
        host.setId(11L);
        car.setOwner(host);
        booking.setCar(car);

        User guest = new User();
        guest.setId(22L);
        booking.setRenter(guest);
        return booking;
    }
}
