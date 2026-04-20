# Check-in Handshake Protocol: Implementation Plan

> **Document Status:** DRAFT v1.0  
> **Author:** Principal Software Architect  
> **Date:** 2025-11-28  
> **Review Status:** Self-Correction Loop Applied ✅

---

## Executive Summary

This document defines the architecture and implementation strategy for the **Check-in Handshake Protocol** - a distributed state machine that synchronizes Host, Guest, and Vehicle Asset transitions from `ACTIVE` → `IN_TRIP` over a 24-hour check-in window.

### Key Architectural Decisions

| Decision | Rationale |
|----------|-----------|
| **Event-Sourced Audit Trail** | Insurance claims require immutable, timestamped action log |
| **IndexedDB + Service Worker** | "Basement Problem" - offline-first photo capture |
| **EXIF Server-Side Validation** | Prevent camera roll uploads (fraud detection) |
| **Geofence via Haversine** | 100m radius check for remote key handoff |
| **Pessimistic Locking on Handshake** | Prevent duplicate "Start Trip" signals |

---

## 0. Regional Constraints: Serbia (RS)

> **CRITICAL:** This implementation is region-locked to Serbia. All timezone, localization, and validation logic assumes Serbian context.

### 0.1 Timezone Policy

| Setting | Value | Rationale |
|---------|-------|----------|
| **System Timezone** | `Europe/Belgrade` (CET/CEST) | Single-timezone deployment, no multi-tz logic |
| **Database Storage** | `TIMESTAMP` (UTC internally) | MySQL converts to session timezone |
| **API Response** | ISO-8601 with offset (`+01:00`/`+02:00`) | Frontend displays local time |
| **Scheduler Cron** | Evaluated in `Europe/Belgrade` | Spring `@Scheduled` uses app timezone |

```java
// application.yml
spring:
  jackson:
    time-zone: Europe/Belgrade
  jpa:
    properties:
      hibernate:
        jdbc:
          time_zone: Europe/Belgrade
```

### 0.2 Localization (Serbian Latin)

| Component | Requirement |
|-----------|-------------|
| **Error Messages** | Serbian Latin (sr-Latn-RS) with fallback to English |
| **UI Text** | UTF-8, support for Č, Ć, Ž, Š, Đ |
| **Database Collation** | `utf8mb4_unicode_ci` (already configured) |
| **Number Format** | Decimal: `,` Thousands: `.` (e.g., `1.234,56 RSD`) |
| **Date Format** | `dd.MM.yyyy.` (e.g., `28.11.2025.`) |

```typescript
// Angular i18n setup
{ provide: LOCALE_ID, useValue: 'sr-Latn-RS' }

// Date formatting
formatDate(date: Date): string {
  return date.toLocaleDateString('sr-Latn-RS', {
    day: '2-digit',
    month: '2-digit', 
    year: 'numeric'
  }); // Output: "28.11.2025."
}
```

### 0.3 Serbian Character Handling in Name Matching

**Challenge:** Serbian names contain diacritics (Č, Ć, Ž, Š, Đ) that OCR may extract as ASCII equivalents.

| Serbian | ASCII Equivalent | Example |
|---------|------------------|----------|
| Đ, đ | Dj, dj | Đorđević → Djordjevic |
| Ž, ž | Z, z | Živković → Zivkovic |
| Č, č | C, c | Čabrić → Cabric |
| Ć, ć | C, c | Ćirić → Ciric |
| Š, š | S, s | Šarić → Saric |

```java
@Service
public class SerbianNameNormalizer {
    
    private static final Map<String, String> SERBIAN_TO_ASCII = Map.ofEntries(
        Map.entry("Đ", "Dj"), Map.entry("đ", "dj"),
        Map.entry("Ž", "Z"),  Map.entry("ž", "z"),
        Map.entry("Č", "C"),  Map.entry("č", "c"),
        Map.entry("Ć", "C"),  Map.entry("ć", "c"),
        Map.entry("Š", "S"),  Map.entry("š", "s")
    );
    
    /**
     * Normalize Serbian name for fuzzy matching.
     * Converts both directions: Đorđević ↔ Djordjevic
     */
    public String normalize(String name) {
        if (name == null) return null;
        
        String normalized = name.trim();
        
        // Step 1: Convert Serbian diacritics to ASCII
        for (Map.Entry<String, String> entry : SERBIAN_TO_ASCII.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }
        
        // Step 2: Uppercase for comparison
        return normalized.toUpperCase(Locale.ROOT);
    }
    
    /**
     * Enhanced fuzzy match that accounts for Serbian character equivalence.
     * Returns score 0.0 - 1.0
     */
    public double fuzzyMatch(String extracted, String profile) {
        String normExtracted = normalize(extracted);
        String normProfile = normalize(profile);
        
        // Use Jaro-Winkler for fuzzy matching
        return new JaroWinklerSimilarity().apply(normExtracted, normProfile);
    }
}
```

### 0.4 Network Considerations (Rural Serbia)

**Challenge:** Rural areas (Vojvodina farmland, mountain regions) have high latency and intermittent connectivity.

| Parameter | Default | Serbia Override | Rationale |
|-----------|---------|-----------------|----------|
| **SW Sync Timeout** | 30s | 120s | Rural 3G can be 5-10s RTT |
| **Photo Upload Chunk Size** | 1MB | 512KB | Smaller chunks = better resume |
| **Retry Attempts** | 3 | 5 | More attempts for flaky connections |
| **Retry Backoff** | 1s, 3s, 9s | 2s, 5s, 15s, 30s, 60s | Longer backoff for congested networks |
| **Offline Queue TTL** | 24h | 72h | Photos may sync days later in rural areas |

```typescript
// check-in-offline.service.ts - Serbia-optimized

private readonly SYNC_CONFIG = {
  timeout: 120_000,        // 2 minutes (was 30s)
  chunkSize: 512 * 1024,   // 512KB chunks
  maxRetries: 5,
  retryDelays: [2000, 5000, 15000, 30000, 60000], // Aggressive backoff
  queueTTL: 72 * 60 * 60 * 1000, // 72 hours
};

async uploadWithRetry(photo: PendingPhotoEntry): Promise<void> {
  for (let attempt = 0; attempt < this.SYNC_CONFIG.maxRetries; attempt++) {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(
        () => controller.abort(), 
        this.SYNC_CONFIG.timeout
      );
      
      await this.uploadPhoto(photo, controller.signal);
      clearTimeout(timeout);
      return;
      
    } catch (error) {
      if (attempt < this.SYNC_CONFIG.maxRetries - 1) {
        await this.delay(this.SYNC_CONFIG.retryDelays[attempt]);
      } else {
        throw error;
      }
    }
  }
}
```

---

## 1. Current State Analysis

### 1.1 Existing Booking Flow (Verified from Codebase)

```
PENDING_APPROVAL → [Host Approve] → ACTIVE → [Trip Ends] → COMPLETED
                 → [Host Decline] → DECLINED
                 → [Auto-Expire]  → EXPIRED_SYSTEM
                 → [Cancel]       → CANCELLED
```

**Gap Identified:** No intermediate state between `ACTIVE` (host approved) and trip start. The system assumes instant transition.

### 1.2 Codebase Audit Findings

| Component | Current State | Gap |
|-----------|---------------|-----|
| `BookingStatus.java` | 7 statuses defined | Missing: `CHECK_IN_OPEN`, `CHECK_IN_GUEST_PENDING`, `IN_TRIP` |
| `Booking.java` | No check-in fields | Missing: `checkInSessionId`, `hostCheckInAt`, `guestCheckInAt`, odometer/fuel snapshots |
| `BookingScheduler.java` | 15-min cron for expiry | Needs: 1-hour cron for check-in window trigger |
| `NotificationType.java` | 9 types | Missing: `CHECK_IN_OPENED`, `CHECK_IN_HOST_COMPLETE`, `CHECK_IN_GUEST_REQUIRED`, `TRIP_STARTED`, `NO_SHOW_ALERT` |
| Frontend `ngsw-config.json` | Asset caching only | Missing: Background Sync for offline uploads |
| `SecurityHeadersFilter.java` | Blocks geolocation | Must allow geolocation for GPS-based handoff |

### 1.3 Database Schema (Current)

```sql
-- From Booking.java entity mapping
Table: bookings
├── id (BIGINT, PK)
├── version (BIGINT) -- Optimistic locking ✅
├── status (VARCHAR) -- Enum: PENDING_APPROVAL, ACTIVE, etc.
├── start_date, end_date (DATE)
├── pickup_time_window, pickup_time (VARCHAR, TIME)
├── car_id, renter_id (FK)
├── approved_at, approved_by (TIMESTAMP, FK)
├── cancelled_at, cancelled_by (TIMESTAMP, ENUM)
└── payment_status (VARCHAR) -- PENDING, AUTHORIZED, RELEASED
```

---

## 2. Proposed State Machine

### 2.1 Extended Status Enum

```java
public enum BookingStatus {
    // Existing (preserve backwards compatibility)
    PENDING_APPROVAL,   // Guest requested, awaiting host
    ACTIVE,             // Host approved, awaiting check-in window
    DECLINED,
    CANCELLED,
    COMPLETED,
    EXPIRED,
    EXPIRED_SYSTEM,
    
    // NEW: Check-in Workflow States
    CHECK_IN_OPEN,           // T-24h: Check-in window opened, host can upload
    CHECK_IN_HOST_COMPLETE,  // Host finished photos/odometer, guest turn
    CHECK_IN_COMPLETE,       // Both parties verified, awaiting handshake
    IN_TRIP,                 // Trip active (replaces implicit ACTIVE during trip)
    
    // NEW: Edge Case States
    NO_SHOW_HOST,            // Host failed to appear within grace period
    NO_SHOW_GUEST            // Guest failed to appear within grace period
}
```

### 2.2 State Transition Diagram

```
                     ┌─────────────────────────────────────────────────┐
                     │                    TIMELINE                      │
                     └─────────────────────────────────────────────────┘
                     
T-48h         T-24h            T-0 (Trip Start)       T+30m          T+End
  │             │                    │                   │              │
  ▼             ▼                    ▼                   ▼              ▼
ACTIVE ──► CHECK_IN_OPEN ──► CHECK_IN_HOST_COMPLETE ──► IN_TRIP ──► COMPLETED
              │                    │                      ▲
              │                    ▼                      │
              │           CHECK_IN_COMPLETE ──────────────┘
              │                    │                (Handshake)
              │                    │
              ▼                    ▼
        (Host Timeout)       (Guest Timeout)
              │                    │
              ▼                    ▼
        NO_SHOW_HOST          NO_SHOW_GUEST
```

### 2.3 Transition Rules Matrix

| From State | Trigger | To State | Guards | Side Effects |
|------------|---------|----------|--------|--------------|
| ACTIVE | `T-24h cron` | CHECK_IN_OPEN | `now >= startDate - 24h` | Generate `checkInSessionId`, notify host |
| CHECK_IN_OPEN | Host completes upload | CHECK_IN_HOST_COMPLETE | `photos.count >= 8`, `exifValid`, `odoValid` | Notify guest, enable guest UI |
| CHECK_IN_HOST_COMPLETE | Guest completes ID/sign-off | CHECK_IN_COMPLETE | `idVerified`, `conditionAck` | Enable "Start Trip" button |
| CHECK_IN_COMPLETE | Both confirm handshake | IN_TRIP | `hostConfirmed && guestConfirmed` | Lock dates, start billing, audit log |
| CHECK_IN_OPEN | `T+30m` without host action | NO_SHOW_HOST | `now > startDate + 30m` | Notify guest, refund flow |
| CHECK_IN_HOST_COMPLETE | `T+30m` without guest action | NO_SHOW_GUEST | `now > startDate + 30m` | Notify host, penalty flow |

---

## 3. Database Schema Changes

### 3.1 Flyway Migration: `V13__check_in_workflow.sql`

```sql
-- ============================================================================
-- V13: Check-in Handshake Protocol Schema
-- ============================================================================

-- 3.1.1 Extend bookings table with check-in fields
ALTER TABLE bookings
    ADD COLUMN check_in_session_id VARCHAR(36) DEFAULT NULL 
        COMMENT 'UUID generated at T-24h, correlates all check-in events',
    ADD COLUMN check_in_opened_at TIMESTAMP NULL 
        COMMENT 'When check-in window was triggered by scheduler',
    ADD COLUMN host_check_in_at TIMESTAMP NULL 
        COMMENT 'When host completed photo/odometer upload',
    ADD COLUMN guest_check_in_at TIMESTAMP NULL 
        COMMENT 'When guest completed ID verification',
    ADD COLUMN handshake_at TIMESTAMP NULL 
        COMMENT 'When both parties confirmed trip start',
    ADD COLUMN trip_started_at TIMESTAMP NULL 
        COMMENT 'Actual trip start (may differ from scheduled startDate)',
    ADD COLUMN trip_ended_at TIMESTAMP NULL 
        COMMENT 'Actual trip end (for early returns)',
    
    -- Odometer/Fuel snapshots (fraud prevention)
    ADD COLUMN start_odometer INT UNSIGNED NULL 
        COMMENT 'Odometer reading at trip start (from host)',
    ADD COLUMN end_odometer INT UNSIGNED NULL 
        COMMENT 'Odometer reading at trip end (for checkout)',
    ADD COLUMN start_fuel_level TINYINT UNSIGNED NULL 
        COMMENT 'Fuel level 0-100% at trip start',
    ADD COLUMN end_fuel_level TINYINT UNSIGNED NULL,
    
    -- Remote handoff (Turo Go style)
    ADD COLUMN lockbox_code_encrypted VARBINARY(256) NULL 
        COMMENT 'AES-256-GCM encrypted lockbox code',
    ADD COLUMN lockbox_code_revealed_at TIMESTAMP NULL 
        COMMENT 'When code was decrypted for guest (audit)',
    
    -- Geofence validation
    ADD COLUMN car_latitude DECIMAL(10, 8) NULL,
    ADD COLUMN car_longitude DECIMAL(11, 8) NULL,
    ADD COLUMN guest_check_in_latitude DECIMAL(10, 8) NULL,
    ADD COLUMN guest_check_in_longitude DECIMAL(11, 8) NULL,
    ADD COLUMN geofence_distance_meters INT NULL 
        COMMENT 'Calculated Haversine distance at handshake';

-- 3.1.2 Indexes for scheduler queries
CREATE INDEX idx_booking_checkin_window 
    ON bookings (status, start_date, check_in_opened_at)
    COMMENT 'Scheduler: Find ACTIVE bookings needing check-in window trigger';

CREATE INDEX idx_booking_noshow_check 
    ON bookings (status, start_date, host_check_in_at, guest_check_in_at)
    COMMENT 'Scheduler: Find potential no-show candidates';

-- ============================================================================
-- 3.2 Check-in Event Audit Trail (Immutable Append-Only)
-- ============================================================================

CREATE TABLE check_in_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    check_in_session_id VARCHAR(36) NOT NULL,
    event_type ENUM(
        'CHECK_IN_OPENED',
        'HOST_PHOTO_UPLOADED',
        'HOST_ODOMETER_SUBMITTED',
        'HOST_FUEL_SUBMITTED',
        'HOST_LOCKBOX_SUBMITTED',
        'HOST_SECTION_COMPLETE',
        'GUEST_ID_VERIFIED',
        'GUEST_CONDITION_ACKNOWLEDGED',
        'GUEST_HOTSPOT_MARKED',
        'GUEST_SECTION_COMPLETE',
        'HANDSHAKE_HOST_CONFIRMED',
        'HANDSHAKE_GUEST_CONFIRMED',
        'TRIP_STARTED',
        'GEOFENCE_CHECK_PASSED',
        'GEOFENCE_CHECK_FAILED',
        'NO_SHOW_TRIGGERED',
        'LOCKBOX_CODE_REVEALED'
    ) NOT NULL,
    actor_id BIGINT NOT NULL COMMENT 'User ID who triggered event',
    actor_role ENUM('HOST', 'GUEST', 'SYSTEM') NOT NULL,
    event_timestamp TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) 
        COMMENT 'Millisecond precision for ordering',
    metadata JSON NULL COMMENT 'Event-specific data (photo IDs, GPS coords, etc.)',
    client_timestamp TIMESTAMP(3) NULL 
        COMMENT 'Device time for offline sync (may differ from server time)',
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,
    
    -- Immutability enforcement (no UPDATE/DELETE triggers)
    CONSTRAINT fk_checkin_event_booking FOREIGN KEY (booking_id) 
        REFERENCES bookings(id) ON DELETE RESTRICT,
    
    INDEX idx_checkin_event_session (check_in_session_id, event_timestamp),
    INDEX idx_checkin_event_booking (booking_id, event_type)
) ENGINE=InnoDB 
  ROW_FORMAT=COMPRESSED
  COMMENT='Immutable audit log for insurance/dispute resolution';

-- Trigger to prevent updates (immutability)
DELIMITER //
CREATE TRIGGER trg_checkin_events_immutable
BEFORE UPDATE ON check_in_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'IMMUTABLE_VIOLATION: check_in_events cannot be updated';
END //

CREATE TRIGGER trg_checkin_events_nodelete
BEFORE DELETE ON check_in_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'IMMUTABLE_VIOLATION: check_in_events cannot be deleted';
END //
DELIMITER ;

-- ============================================================================
-- 3.3 Check-in Photos (Secure Storage)
-- ============================================================================

CREATE TABLE check_in_photos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    check_in_session_id VARCHAR(36) NOT NULL,
    photo_type ENUM(
        'HOST_EXTERIOR_FRONT',
        'HOST_EXTERIOR_REAR',
        'HOST_EXTERIOR_LEFT',
        'HOST_EXTERIOR_RIGHT',
        'HOST_INTERIOR_DASHBOARD',
        'HOST_INTERIOR_REAR',
        'HOST_ODOMETER',
        'HOST_FUEL_GAUGE',
        'HOST_DAMAGE_PREEXISTING',
        'GUEST_DAMAGE_NOTED',
        'GUEST_HOTSPOT'
    ) NOT NULL,
    storage_bucket ENUM('CHECKIN_STANDARD', 'CHECKIN_PII') NOT NULL DEFAULT 'CHECKIN_STANDARD'
        COMMENT 'CHECKIN_PII for ID photos - restricted access',
    storage_key VARCHAR(500) NOT NULL COMMENT 'S3/GCS path or secure storage key',
    original_filename VARCHAR(255),
    mime_type VARCHAR(50) NOT NULL,
    file_size_bytes INT UNSIGNED NOT NULL,
    
    -- EXIF Validation (fraud prevention)
    exif_timestamp TIMESTAMP NULL COMMENT 'Photo taken timestamp from EXIF',
    exif_latitude DECIMAL(10, 8) NULL,
    exif_longitude DECIMAL(11, 8) NULL,
    exif_device_model VARCHAR(100) NULL,
    exif_validation_status ENUM('PENDING', 'VALID', 'REJECTED_TOO_OLD', 'REJECTED_NO_EXIF', 'REJECTED_LOCATION_MISMATCH') 
        NOT NULL DEFAULT 'PENDING',
    exif_validation_message VARCHAR(500) NULL,
    
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_uploaded_at TIMESTAMP NULL COMMENT 'Device time for offline uploads',
    
    -- Soft delete for compliance (never hard delete photos)
    deleted_at TIMESTAMP NULL,
    deleted_reason VARCHAR(255) NULL,
    
    CONSTRAINT fk_checkin_photo_booking FOREIGN KEY (booking_id) 
        REFERENCES bookings(id) ON DELETE RESTRICT,
    
    INDEX idx_checkin_photo_session (check_in_session_id, photo_type),
    INDEX idx_checkin_photo_booking (booking_id, photo_type),
    INDEX idx_checkin_photo_exif_status (exif_validation_status)
) ENGINE=InnoDB 
  COMMENT='Check-in photos with EXIF validation for fraud prevention';

-- ============================================================================
-- 3.4 Guest Identity Verification (PII-Separated)
-- ============================================================================

CREATE TABLE check_in_id_verifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE COMMENT 'One verification per booking',
    check_in_session_id VARCHAR(36) NOT NULL,
    guest_id BIGINT NOT NULL,
    
    -- Liveness check
    liveness_passed BOOLEAN NOT NULL DEFAULT FALSE,
    liveness_score DECIMAL(5, 4) NULL COMMENT '0.0000 to 1.0000 confidence',
    liveness_provider VARCHAR(50) NULL COMMENT 'e.g., AWS Rekognition, Onfido',
    liveness_checked_at TIMESTAMP NULL,
    
    -- Document verification
    document_type ENUM('DRIVERS_LICENSE', 'PASSPORT', 'NATIONAL_ID') NULL,
    document_country VARCHAR(3) NULL COMMENT 'ISO 3166-1 alpha-3',
    document_expiry DATE NULL,
    document_expiry_valid BOOLEAN GENERATED ALWAYS AS (
        document_expiry > (SELECT end_date FROM bookings WHERE bookings.id = booking_id)
    ) STORED COMMENT 'Auto-check: DL must be valid through trip end',
    
    -- Name matching (Serbian-aware: Đ=Dj, Ž=Z, Č=Ć=C, Š=S)
    extracted_first_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    extracted_last_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    extracted_name_normalized VARCHAR(200) NULL COMMENT 'ASCII-normalized for matching (Đorđević→DJORDJEVIC)',
    profile_name_normalized VARCHAR(200) NULL COMMENT 'User profile name normalized',
    name_match_score DECIMAL(5, 4) NULL COMMENT 'Jaro-Winkler on normalized names',
    name_match_passed BOOLEAN GENERATED ALWAYS AS (name_match_score >= 0.80) STORED,
    
    -- Overall status
    verification_status ENUM(
        'PENDING',
        'PASSED',
        'FAILED_LIVENESS',
        'FAILED_DOCUMENT_EXPIRED',
        'FAILED_NAME_MISMATCH',
        'FAILED_DOCUMENT_UNREADABLE',
        'MANUAL_REVIEW'
    ) NOT NULL DEFAULT 'PENDING',
    
    -- PII storage (encrypted, separate bucket)
    id_photo_storage_key VARCHAR(500) NULL COMMENT 'Encrypted in CHECKIN_PII bucket',
    selfie_storage_key VARCHAR(500) NULL,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at TIMESTAMP NULL,
    
    CONSTRAINT fk_id_verification_booking FOREIGN KEY (booking_id) 
        REFERENCES bookings(id) ON DELETE RESTRICT,
    
    INDEX idx_id_verification_guest (guest_id),
    INDEX idx_id_verification_status (verification_status)
) ENGINE=InnoDB 
  COMMENT='Guest ID verification with biometric matching - PII data';
```

---

## 4. API Contract Definitions

### 4.1 Check-in DTOs

```java
// ===== REQUEST DTOs =====

@Data
@Builder
public class HostCheckInSubmissionDTO {
    @NotNull
    private Long bookingId;
    
    @NotNull
    @Min(0)
    private Integer odometerReading;
    
    @NotNull
    @Min(0)
    @Max(100)
    private Integer fuelLevelPercent;
    
    @Size(min = 8, max = 15)
    private List<String> photoIds; // References to uploaded photos
    
    // Optional: Remote handoff
    @Size(max = 10)
    private String lockboxCode; // Will be encrypted server-side
    
    // Car location for geofence
    private Double carLatitude;
    private Double carLongitude;
}

@Data
@Builder
public class GuestConditionAcknowledgmentDTO {
    @NotNull
    private Long bookingId;
    
    @NotNull
    private Boolean conditionAccepted; // "I confirm vehicle condition"
    
    private List<HotspotMarkingDTO> hotspots; // Optional damage notes
    
    @NotNull
    private Double guestLatitude;
    
    @NotNull
    private Double guestLongitude;
}

@Data
public class HotspotMarkingDTO {
    @NotNull
    private String photoId;
    
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double xPercent; // 0.0 = left, 1.0 = right
    
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double yPercent;
    
    @Size(max = 500)
    private String description;
}

@Data
@Builder
public class HandshakeConfirmationDTO {
    @NotNull
    private Long bookingId;
    
    @NotNull
    private Boolean confirmed;
    
    // For in-person: Host verifies physical ID
    private Boolean hostVerifiedPhysicalId;
    
    // Guest location (for geofence validation)
    private Double latitude;
    private Double longitude;
}

// ===== RESPONSE DTOs =====

@Data
@Builder
public class CheckInStatusDTO {
    private Long bookingId;
    private String checkInSessionId;
    private BookingStatus status;
    
    // Phase completion
    private boolean hostCheckInComplete;
    private boolean guestCheckInComplete;
    private boolean handshakeReady;
    
    // Timestamps
    private LocalDateTime checkInOpenedAt;
    private LocalDateTime hostCompletedAt;
    private LocalDateTime guestCompletedAt;
    
    // Host data (visible to guest after host completes)
    private List<CheckInPhotoDTO> vehiclePhotos;
    private Integer odometerReading;
    private Integer fuelLevelPercent;
    
    // Remote handoff
    private boolean lockboxAvailable;
    private boolean geofenceValid;
    private Integer geofenceDistanceMeters;
    
    // Deadlines
    private LocalDateTime noShowDeadline;
    private long minutesUntilNoShow;
}

@Data
@Builder
public class CheckInPhotoDTO {
    private String photoId;
    private String photoType;
    private String url; // Pre-signed URL, expires in 1 hour
    private LocalDateTime uploadedAt;
    private String exifValidationStatus;
}
```

### 4.2 REST Endpoints

```java
@RestController
@RequestMapping("/api/bookings/{bookingId}/check-in")
@PreAuthorize("isAuthenticated()")
public class CheckInController {

    // ===== STATUS =====
    
    @GetMapping("/status")
    @PreAuthorize("@bookingSecurity.canAccessBooking(#bookingId, principal.id)")
    public ResponseEntity<CheckInStatusDTO> getCheckInStatus(@PathVariable Long bookingId);

    // ===== HOST WORKFLOW =====
    
    @PostMapping("/host/photos")
    @PreAuthorize("@bookingSecurity.isOwner(#bookingId, principal.id)")
    public ResponseEntity<CheckInPhotoDTO> uploadHostPhoto(
        @PathVariable Long bookingId,
        @RequestPart("file") MultipartFile file,
        @RequestParam("photoType") String photoType);
    
    @PostMapping("/host/complete")
    @PreAuthorize("@bookingSecurity.isOwner(#bookingId, principal.id)")
    public ResponseEntity<CheckInStatusDTO> completeHostCheckIn(
        @PathVariable Long bookingId,
        @Valid @RequestBody HostCheckInSubmissionDTO submission);

    // ===== GUEST WORKFLOW =====
    
    @PostMapping("/guest/id-verification/start")
    @PreAuthorize("@bookingSecurity.isRenter(#bookingId, principal.id)")
    public ResponseEntity<IdVerificationSessionDTO> startIdVerification(@PathVariable Long bookingId);
    
    @PostMapping("/guest/condition-ack")
    @PreAuthorize("@bookingSecurity.isRenter(#bookingId, principal.id)")
    public ResponseEntity<CheckInStatusDTO> acknowledgeCondition(
        @PathVariable Long bookingId,
        @Valid @RequestBody GuestConditionAcknowledgmentDTO ack);

    // ===== HANDSHAKE =====
    
    @PostMapping("/handshake")
    @PreAuthorize("@bookingSecurity.canAccessBooking(#bookingId, principal.id)")
    public ResponseEntity<CheckInStatusDTO> confirmHandshake(
        @PathVariable Long bookingId,
        @Valid @RequestBody HandshakeConfirmationDTO confirmation);
    
    // ===== REMOTE HANDOFF (Turo Go) =====
    
    @GetMapping("/lockbox-code")
    @PreAuthorize("@bookingSecurity.isRenter(#bookingId, principal.id)")
    public ResponseEntity<LockboxCodeDTO> revealLockboxCode(@PathVariable Long bookingId);

    // ===== NO-SHOW =====
    
    @PostMapping("/report-no-show")
    @PreAuthorize("@bookingSecurity.canAccessBooking(#bookingId, principal.id)")
    public ResponseEntity<NoShowReportDTO> reportNoShow(
        @PathVariable Long bookingId,
        @RequestParam("party") String party); // HOST or GUEST
}
```

---

## 5. Backend Service Architecture

### 5.1 New Services

```
org.example.rentoza.booking.checkin/
├── CheckInService.java           # Core orchestrator
├── CheckInScheduler.java         # Cron jobs for window opening
├── CheckInEventService.java      # Audit trail writer
├── ExifValidationService.java    # Photo metadata extraction
├── GeofenceService.java          # Haversine distance calc
├── LockboxEncryptionService.java # AES-256-GCM for codes
└── IdVerificationClient.java     # External ID verification API
```

### 5.2 CheckInScheduler (Cron Jobs)

```java
@Component
@ConditionalOnProperty(name = "app.checkin.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CheckInScheduler {

    /**
     * Trigger Check-in Window (T-24h)
     * Cron: Every hour at minute 0
     * 
     * Finds ACTIVE bookings starting within next 24 hours
     * Transitions to CHECK_IN_OPEN and notifies host
     */
    @Scheduled(cron = "${app.checkin.scheduler.window-cron:0 0 * * * *}")
    @Transactional
    public void openCheckInWindows() {
        LocalDate targetDate = LocalDate.now().plusDays(1);
        
        List<Booking> eligibleBookings = bookingRepository.findByStatusAndStartDateBetween(
            BookingStatus.ACTIVE,
            LocalDate.now(),
            targetDate
        );
        
        for (Booking booking : eligibleBookings) {
            if (booking.getCheckInOpenedAt() == null) {
                booking.setStatus(BookingStatus.CHECK_IN_OPEN);
                booking.setCheckInSessionId(UUID.randomUUID().toString());
                booking.setCheckInOpenedAt(LocalDateTime.now());
                
                eventService.recordEvent(booking, CheckInEventType.CHECK_IN_OPENED, "SYSTEM");
                notificationService.sendCheckInOpened(booking);
            }
        }
    }

    /**
     * No-Show Detection (T+30m)
     * Cron: Every 10 minutes
     */
    @Scheduled(cron = "${app.checkin.scheduler.noshow-cron:0 0/10 * * * *}")
    @Transactional
    public void detectNoShows() {
        LocalDateTime noShowThreshold = LocalDateTime.now().minusMinutes(30);
        
        // Host no-show: CHECK_IN_OPEN but no host action past start + 30m
        List<Booking> hostNoShows = bookingRepository.findPotentialHostNoShows(
            BookingStatus.CHECK_IN_OPEN, noShowThreshold);
        
        // Guest no-show: CHECK_IN_HOST_COMPLETE but no guest action past start + 30m
        List<Booking> guestNoShows = bookingRepository.findPotentialGuestNoShows(
            BookingStatus.CHECK_IN_HOST_COMPLETE, noShowThreshold);
        
        // Process no-shows...
    }
}
```

### 5.3 EXIF Validation Service

```java
@Service
@Slf4j
public class ExifValidationService {

    private static final Duration MAX_PHOTO_AGE = Duration.ofHours(24);

    /**
     * Validate photo EXIF metadata for fraud prevention.
     * 
     * Checks:
     * 1. EXIF exists (reject screenshots/downloads)
     * 2. Photo taken within 24 hours (reject camera roll uploads)
     * 3. GPS coordinates present (optional, for location verification)
     */
    public ExifValidationResult validate(byte[] photoBytes, LocalDateTime checkInOpenedAt) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(
                new ByteArrayInputStream(photoBytes));
            
            // 1. Check for EXIF directory
            ExifSubIFDDirectory exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exifDir == null) {
                return ExifValidationResult.rejected("REJECTED_NO_EXIF", 
                    "Photo has no EXIF metadata - may be a screenshot or downloaded image");
            }
            
            // 2. Check photo timestamp
            Date dateOriginal = exifDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            if (dateOriginal == null) {
                return ExifValidationResult.rejected("REJECTED_NO_EXIF",
                    "Photo has no timestamp - cannot verify recency");
            }
            
            LocalDateTime photoTime = dateOriginal.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
            
            if (photoTime.isBefore(checkInOpenedAt.minus(MAX_PHOTO_AGE))) {
                return ExifValidationResult.rejected("REJECTED_TOO_OLD",
                    String.format("Photo taken at %s is older than 24 hours before check-in opened at %s",
                        photoTime, checkInOpenedAt));
            }
            
            // 3. Extract GPS (optional)
            GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            Double latitude = null, longitude = null;
            if (gpsDir != null && gpsDir.getGeoLocation() != null) {
                latitude = gpsDir.getGeoLocation().getLatitude();
                longitude = gpsDir.getGeoLocation().getLongitude();
            }
            
            // 4. Extract device info
            String deviceModel = null;
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                deviceModel = ifd0.getString(ExifIFD0Directory.TAG_MODEL);
            }
            
            return ExifValidationResult.valid(photoTime, latitude, longitude, deviceModel);
            
        } catch (Exception e) {
            log.error("EXIF extraction failed", e);
            return ExifValidationResult.rejected("REJECTED_NO_EXIF", 
                "Failed to read image metadata: " + e.getMessage());
        }
    }
}
```

### 5.4 Geofence Service

```java
@Service
public class GeofenceService {

    private static final int UNLOCK_RADIUS_METERS = 100;
    private static final double EARTH_RADIUS_METERS = 6371000;

    /**
     * Validate guest is within 100m of car location using Haversine formula.
     */
    public GeofenceResult validateProximity(
            double carLat, double carLon,
            double guestLat, double guestLon) {
        
        double distance = haversineDistance(carLat, carLon, guestLat, guestLon);
        boolean withinRadius = distance <= UNLOCK_RADIUS_METERS;
        
        return GeofenceResult.builder()
            .distanceMeters((int) Math.round(distance))
            .withinRadius(withinRadius)
            .requiredRadiusMeters(UNLOCK_RADIUS_METERS)
            .build();
    }

    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_METERS * c;
    }
}
```

### 5.5 Handshake Idempotency

```java
@Service
@Slf4j
public class CheckInService {

    /**
     * Process handshake confirmation with idempotency protection.
     * Uses pessimistic locking to prevent duplicate "Start Trip" signals.
     */
    @Transactional
    public CheckInStatusDTO confirmHandshake(Long bookingId, Long userId, HandshakeConfirmationDTO dto) {
        // Acquire pessimistic lock
        Booking booking = bookingRepository.findByIdWithPessimisticLock(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        
        // Validate state (idempotency check)
        if (booking.getStatus() == BookingStatus.IN_TRIP) {
            log.info("Handshake already completed for booking {} - idempotent return", bookingId);
            return mapToStatus(booking);
        }
        
        if (booking.getStatus() != BookingStatus.CHECK_IN_COMPLETE) {
            throw new IllegalStateException("Check-in not complete. Current status: " + booking.getStatus());
        }
        
        // Determine actor role
        boolean isHost = booking.getCar().getOwner().getId().equals(userId);
        boolean isGuest = booking.getRenter().getId().equals(userId);
        
        if (isHost) {
            if (booking.getHostHandshakeAt() != null) {
                log.info("Host already confirmed handshake for booking {}", bookingId);
            } else {
                booking.setHostHandshakeAt(LocalDateTime.now());
                eventService.recordEvent(booking, HANDSHAKE_HOST_CONFIRMED, userId, "HOST");
            }
        } else if (isGuest) {
            // Geofence check for remote handoff
            if (booking.getLockboxCodeEncrypted() != null && dto.getLatitude() != null) {
                GeofenceResult geoResult = geofenceService.validateProximity(
                    booking.getCarLatitude(), booking.getCarLongitude(),
                    dto.getLatitude(), dto.getLongitude());
                
                if (!geoResult.isWithinRadius()) {
                    throw new GeofenceViolationException(
                        String.format("You must be within %dm of the car. Current distance: %dm",
                            geoResult.getRequiredRadiusMeters(), geoResult.getDistanceMeters()));
                }
                
                booking.setGuestCheckInLatitude(BigDecimal.valueOf(dto.getLatitude()));
                booking.setGuestCheckInLongitude(BigDecimal.valueOf(dto.getLongitude()));
                booking.setGeofenceDistanceMeters(geoResult.getDistanceMeters());
            }
            
            if (booking.getGuestHandshakeAt() != null) {
                log.info("Guest already confirmed handshake for booking {}", bookingId);
            } else {
                booking.setGuestHandshakeAt(LocalDateTime.now());
                eventService.recordEvent(booking, HANDSHAKE_GUEST_CONFIRMED, userId, "GUEST");
            }
        } else {
            throw new AccessDeniedException("User is not a participant in this booking");
        }
        
        // Check if both parties confirmed → transition to IN_TRIP
        if (booking.getHostHandshakeAt() != null && booking.getGuestHandshakeAt() != null) {
            booking.setStatus(BookingStatus.IN_TRIP);
            booking.setHandshakeAt(LocalDateTime.now());
            booking.setTripStartedAt(LocalDateTime.now());
            
            eventService.recordEvent(booking, TRIP_STARTED, userId, isHost ? "HOST" : "GUEST");
            
            log.info("Trip started for booking {} - both parties confirmed handshake", bookingId);
            notificationService.sendTripStarted(booking);
        }
        
        return mapToStatus(booking);
    }
}
```

---

## 6. Frontend Architecture

### 6.1 Offline-First Photo Upload Strategy

**The "Basement Problem":** Guest takes photos in a signal-dead zone (underground parking, rural area).

#### Solution: IndexedDB + Service Worker Background Sync

```typescript
// check-in-offline.service.ts

@Injectable({ providedIn: 'root' })
export class CheckInOfflineService {
  private readonly DB_NAME = 'rentoza-checkin';
  private readonly STORE_NAME = 'pending-photos';
  private db: IDBDatabase | null = null;

  async init(): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.DB_NAME, 1);
      
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        if (!db.objectStoreNames.contains(this.STORE_NAME)) {
          db.createObjectStore(this.STORE_NAME, { keyPath: 'localId' });
        }
      };
      
      request.onsuccess = () => {
        this.db = request.result;
        resolve();
      };
      
      request.onerror = () => reject(request.error);
    });
  }

  /**
   * Save photo locally for offline-first upload.
   * Returns immediately with optimistic UI state.
   */
  async queuePhoto(photo: PendingPhoto): Promise<string> {
    const localId = `local-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    
    const entry: PendingPhotoEntry = {
      localId,
      bookingId: photo.bookingId,
      photoType: photo.photoType,
      blob: photo.blob,
      clientTimestamp: new Date().toISOString(),
      uploadStatus: 'PENDING',
      retryCount: 0,
    };
    
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction(this.STORE_NAME, 'readwrite');
      const store = tx.objectStore(this.STORE_NAME);
      const request = store.add(entry);
      
      request.onsuccess = () => {
        // Register background sync
        this.registerBackgroundSync();
        resolve(localId);
      };
      
      request.onerror = () => reject(request.error);
    });
  }

  /**
   * Register Service Worker background sync for pending uploads.
   */
  private async registerBackgroundSync(): Promise<void> {
    if ('serviceWorker' in navigator && 'sync' in ServiceWorkerRegistration.prototype) {
      const registration = await navigator.serviceWorker.ready;
      await (registration as any).sync.register('checkin-photo-upload');
    }
  }

  /**
   * Get all pending photos (for UI display).
   */
  async getPendingPhotos(bookingId: number): Promise<PendingPhotoEntry[]> {
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction(this.STORE_NAME, 'readonly');
      const store = tx.objectStore(this.STORE_NAME);
      const request = store.getAll();
      
      request.onsuccess = () => {
        const all = request.result as PendingPhotoEntry[];
        resolve(all.filter(p => p.bookingId === bookingId));
      };
      
      request.onerror = () => reject(request.error);
    });
  }
}

interface PendingPhotoEntry {
  localId: string;
  bookingId: number;
  photoType: string;
  blob: Blob;
  clientTimestamp: string;
  uploadStatus: 'PENDING' | 'UPLOADING' | 'UPLOADED' | 'FAILED';
  serverId?: string;
  retryCount: number;
  lastError?: string;
}
```

#### Service Worker Sync Handler

```typescript
// ngsw-worker-custom.js (extend Angular service worker)

self.addEventListener('sync', (event) => {
  if (event.tag === 'checkin-photo-upload') {
    event.waitUntil(uploadPendingPhotos());
  }
});

async function uploadPendingPhotos() {
  const db = await openDB('rentoza-checkin', 1);
  const tx = db.transaction('pending-photos', 'readwrite');
  const store = tx.objectStore('pending-photos');
  const pending = await store.getAll();
  
  for (const photo of pending) {
    if (photo.uploadStatus !== 'PENDING') continue;
    
    try {
      photo.uploadStatus = 'UPLOADING';
      await store.put(photo);
      
      const formData = new FormData();
      formData.append('file', photo.blob);
      formData.append('photoType', photo.photoType);
      formData.append('clientTimestamp', photo.clientTimestamp);
      
      const response = await fetch(
        `/api/bookings/${photo.bookingId}/check-in/host/photos`,
        {
          method: 'POST',
          body: formData,
          credentials: 'include',
        }
      );
      
      if (response.ok) {
        const result = await response.json();
        photo.uploadStatus = 'UPLOADED';
        photo.serverId = result.photoId;
        await store.put(photo);
        
        // Notify UI
        self.clients.matchAll().then(clients => {
          clients.forEach(client => {
            client.postMessage({
              type: 'PHOTO_UPLOADED',
              localId: photo.localId,
              serverId: result.photoId,
            });
          });
        });
      } else if (response.status === 400) {
        // Permanent failure (e.g., EXIF rejected)
        photo.uploadStatus = 'FAILED';
        photo.lastError = await response.text();
        await store.put(photo);
      } else {
        // Transient failure - will retry on next sync
        photo.uploadStatus = 'PENDING';
        photo.retryCount++;
        await store.put(photo);
      }
    } catch (error) {
      photo.uploadStatus = 'PENDING';
      photo.retryCount++;
      await store.put(photo);
    }
  }
}
```

### 6.2 Check-in Component Structure

```
features/
└── bookings/
    └── check-in/
        ├── check-in.routes.ts
        ├── pages/
        │   ├── check-in-host/
        │   │   ├── check-in-host.component.ts
        │   │   ├── check-in-host.component.html
        │   │   └── check-in-host.component.scss
        │   └── check-in-guest/
        │       ├── check-in-guest.component.ts
        │       ├── check-in-guest.component.html
        │       └── check-in-guest.component.scss
        ├── components/
        │   ├── photo-grid/
        │   ├── odometer-input/
        │   ├── fuel-gauge-input/
        │   ├── condition-viewer/
        │   ├── hotspot-marker/
        │   ├── handshake-swipe/
        │   └── geofence-status/
        └── services/
            ├── check-in.service.ts
            ├── check-in-offline.service.ts
            └── geolocation.service.ts
```

### 6.3 Geolocation Permission Handling

```typescript
// geolocation.service.ts

@Injectable({ providedIn: 'root' })
export class GeolocationService {
  
  async getCurrentPosition(options?: PositionOptions): Promise<GeolocationPosition> {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) {
        reject(new Error('Geolocation not supported'));
        return;
      }
      
      navigator.geolocation.getCurrentPosition(
        resolve,
        (error) => {
          switch (error.code) {
            case error.PERMISSION_DENIED:
              reject(new GeolocationError(
                'PERMISSION_DENIED',
                'Location access denied. Please enable in Settings > Privacy > Location.'
              ));
              break;
            case error.POSITION_UNAVAILABLE:
              reject(new GeolocationError(
                'POSITION_UNAVAILABLE',
                'Location unavailable. Try moving to a location with better signal.'
              ));
              break;
            case error.TIMEOUT:
              reject(new GeolocationError(
                'TIMEOUT',
                'Location request timed out. Please try again.'
              ));
              break;
          }
        },
        {
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 30000,
          ...options,
        }
      );
    });
  }
}
```

### 6.4 Update ngsw-config.json for Background Sync

```json
{
  "$schema": "./node_modules/@angular/service-worker/config/schema.json",
  "index": "/index.html",
  "assetGroups": [
    // ... existing config ...
  ],
  "dataGroups": [
    // ... existing config ...
    {
      "name": "api-checkin",
      "urls": ["/api/bookings/*/check-in/**"],
      "cacheConfig": {
        "strategy": "freshness",
        "maxSize": 20,
        "maxAge": "5m",
        "timeout": "30s"  // Increased for rural Serbia (was 5s)
      }
    }
  ]
}
```

### 6.5 Serbian Localization Setup

```typescript
// app.config.ts - Serbia locale registration

import { registerLocaleData } from '@angular/common';
import localeSrLatn from '@angular/common/locales/sr-Latn';

registerLocaleData(localeSrLatn);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'sr-Latn-RS' },
    // ...
  ]
};
```

```typescript
// check-in-messages.ts - Serbian error messages

export const CHECK_IN_MESSAGES = {
  // Host workflow
  PHOTO_UPLOAD_SUCCESS: 'Fotografija uspešno otpremljena',
  PHOTO_EXIF_REJECTED: 'Fotografija odbijena: snimljena pre više od 24 sata',
  PHOTO_EXIF_MISSING: 'Fotografija nema EXIF podatke - koristite kameru, ne galeriju',
  ODOMETER_INVALID: 'Kilometraža mora biti veća od prethodne ({lastKnown} km)',
  HOST_CHECKIN_COMPLETE: 'Prijem vozila završen. Čekamo gosta.',
  
  // Guest workflow
  ID_VERIFICATION_STARTED: 'Verifikacija identiteta započeta',
  ID_NAME_MISMATCH: 'Ime na dokumentu ne odgovara profilu (podudarnost: {score}%)',
  ID_EXPIRED: 'Dokument je istekao ili ističe pre kraja putovanja',
  CONDITION_ACKNOWLEDGED: 'Stanje vozila potvrđeno',
  
  // Handshake
  HANDSHAKE_WAITING_HOST: 'Čekamo potvrdu domaćina...',
  HANDSHAKE_WAITING_GUEST: 'Čekamo potvrdu gosta...',
  TRIP_STARTED: 'Putovanje je započelo! Srećan put!',
  
  // Geofence
  GEOFENCE_TOO_FAR: 'Morate biti unutar {radius}m od vozila. Trenutna udaljenost: {distance}m',
  GEOFENCE_PASSED: 'Lokacija potvrđena ✓',
  
  // No-show
  NOSHOW_HOST_ALERT: 'Domaćin se nije pojavio. Kontaktirajte podršku.',
  NOSHOW_GUEST_ALERT: 'Gost se nije pojavio u roku od 30 minuta.',
  
  // Offline
  OFFLINE_PHOTOS_PENDING: '{count} fotografija čeka otpremanje',
  OFFLINE_SYNC_IN_PROGRESS: 'Sinhronizacija u toku...',
  OFFLINE_SYNC_COMPLETE: 'Sve fotografije su otpremljene',
  OFFLINE_SYNC_FAILED: 'Neke fotografije nisu otpremljene. Pokušaćemo ponovo.',
} as const;
```

---

## 7. Security Considerations

### 7.1 Security Headers Update

```java
// SecurityHeadersFilter.java - MUST UPDATE

// BEFORE (blocks geolocation):
"geolocation=(), microphone=(), camera=(), payment=()"

// AFTER (allow geolocation for handshake):
"geolocation=(self), microphone=(), camera=(self), payment=()"
```

### 7.2 PII Data Separation

| Data Type | Storage Bucket | Access Control |
|-----------|----------------|----------------|
| Vehicle photos | `CHECKIN_STANDARD` | Host, Guest, Admin |
| ID document photos | `CHECKIN_PII` | Guest (owner), Admin only |
| Selfie/liveness | `CHECKIN_PII` | Guest (owner), Admin only |
| Lockbox codes | `Encrypted in DB` | Decrypt only after guest verification |

### 7.3 Lockbox Code Encryption

```java
@Service
public class LockboxEncryptionService {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    
    @Value("${app.checkin.lockbox.encryption-key}")
    private String base64Key;
    
    public byte[] encrypt(String lockboxCode) throws GeneralSecurityException {
        SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(lockboxCode.getBytes(StandardCharsets.UTF_8));
        
        // Prepend IV to ciphertext
        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        
        return result;
    }
    
    public String decrypt(byte[] encryptedData) throws GeneralSecurityException {
        SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        
        byte[] iv = Arrays.copyOfRange(encryptedData, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(encryptedData, GCM_IV_LENGTH, encryptedData.length);
        
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        
        return new String(plaintext, StandardCharsets.UTF_8);
    }
}
```

---

## 8. Self-Correction Loop: Edge Case Analysis

### 8.1 "What if the user kills the app while uploading?"

**Scenario:** Host takes 10 photos, kills app after 5 are uploaded.

**Mitigation:**
1. IndexedDB persists all 10 photos locally BEFORE any upload starts
2. Each photo has independent `uploadStatus` tracking
3. Service Worker Background Sync retries when app reopens or connectivity restores
4. UI shows: "5/10 uploaded • 5 pending" with local thumbnails for pending photos
5. "Complete Check-in" button disabled until all photos uploaded

**Implementation:**
```typescript
canComplete = computed(() => {
  const pending = this.pendingPhotos();
  const uploaded = this.uploadedPhotos();
  return pending.filter(p => p.uploadStatus === 'PENDING').length === 0 
         && uploaded.length >= 8;
});
```

### 8.2 "What if the Host changes the lockbox code last minute?"

**Scenario:** Host submits code "1234", guest is in transit, host changes to "5678".

**Mitigation:**
1. **Lockbox code is LOCKED after host completes check-in** (state: `CHECK_IN_HOST_COMPLETE`)
2. Add endpoint: `PUT /api/bookings/{id}/check-in/host/lockbox` with guard:
   ```java
   if (booking.getStatus() != BookingStatus.CHECK_IN_OPEN) {
       throw new IllegalStateException("Cannot modify lockbox after check-in submitted");
   }
   ```
3. **Audit trail:** `HOST_LOCKBOX_SUBMITTED` event records original code (encrypted)
4. **Emergency override:** Admin-only endpoint to update lockbox with mandatory reason

### 8.3 "What if GPS is spoofed for geofence?"

**Scenario:** Guest uses mock location app to bypass 100m geofence.

**Mitigation (Defense in Depth):**
1. **Plausibility check:** Compare guest's last known location (from app usage) with check-in location. Flag if > 100km jump in < 1 hour.
2. **Multiple location samples:** Require 3 location readings over 30 seconds, check consistency.
3. **IP geolocation correlation:** Compare GPS with IP-based geolocation (within ~50km tolerance).
4. **Manual review trigger:** If any check fails, flag for manual review but don't block (false positives are costly).

```java
public GeofenceResult validateProximityWithAntiSpoof(
        double carLat, double carLon,
        double guestLat, double guestLon,
        String clientIp,
        List<LocationSample> recentSamples) {
    
    // Basic distance check
    double distance = haversineDistance(carLat, carLon, guestLat, guestLon);
    
    // Anti-spoof: Check location consistency
    boolean suspiciousJump = recentSamples.stream()
        .anyMatch(sample -> {
            double sampleDistance = haversineDistance(guestLat, guestLon, sample.lat, sample.lon);
            Duration timeDelta = Duration.between(sample.timestamp, Instant.now());
            double maxPossibleDistance = timeDelta.toSeconds() * 50; // 50 m/s = 180 km/h
            return sampleDistance > maxPossibleDistance;
        });
    
    // Anti-spoof: IP correlation (best-effort)
    boolean ipMismatch = false;
    try {
        GeoLocation ipLocation = geoIpService.lookup(clientIp);
        if (ipLocation != null) {
            double ipDistance = haversineDistance(guestLat, guestLon, ipLocation.lat, ipLocation.lon);
            ipMismatch = ipDistance > 50000; // 50km tolerance for IP geolocation
        }
    } catch (Exception e) {
        log.warn("IP geolocation lookup failed", e);
    }
    
    return GeofenceResult.builder()
        .distanceMeters((int) Math.round(distance))
        .withinRadius(distance <= 100)
        .spoofingSuspected(suspiciousJump || ipMismatch)
        .build();
}
```

### 8.4 "What if EXIF stripping is enabled on phone?"

**Scenario:** iPhone privacy setting removes EXIF before upload.

**Mitigation:**
1. **Primary:** Require EXIF for at least 50% of photos (configurable)
2. **Fallback:** For EXIF-stripped photos, require live camera capture (not gallery)
   ```typescript
   // Force camera capture, not gallery
   <input type="file" accept="image/*" capture="environment">
   ```
3. **Client-side validation:** Check EXIF before upload, warn user to disable stripping
4. **Grace period:** During rollout, accept EXIF-stripped with warning but log for analysis

### 8.5 "What if backend is down during handshake?"

**Scenario:** Both parties swipe "Start Trip", backend returns 500.

**Mitigation:**
1. **Retry with exponential backoff:** Frontend retries 3 times (1s, 3s, 9s)
2. **Optimistic UI:** Show "Trip Starting..." immediately, revert on final failure
3. **Idempotent endpoint:** Backend checks if handshake already complete before modifying state
4. **Manual fallback:** After 3 failures, show "Contact Support" with booking ID

### 8.6 "What if Host uploads odometer < last known reading?"

**Scenario:** Host enters 50,000 km, but car's last checkout was 52,000 km.

**Mitigation:**
1. **Soft warning:** Allow submission but flag for review
   ```java
   if (newOdo < car.getLastKnownOdometer()) {
       eventService.recordEvent(booking, ODOMETER_ANOMALY_DETECTED, 
           Map.of("submitted", newOdo, "lastKnown", car.getLastKnownOdometer()));
       // Don't block, but mark for audit
   }
   ```
2. **Admin dashboard:** Surface flagged check-ins with odometer anomalies
3. **Guest visibility:** Show warning to guest: "⚠️ Reported odometer (50,000) is lower than previous trip end (52,000)"

---

## 9. Integration Steps

### 9.1 Backend Changes

1. **Flyway Migration:** Create `V13__check_in_workflow.sql` (see Section 3)
2. **Entity Updates:**
   - Add new fields to `Booking.java`
   - Add new enum values to `BookingStatus.java`
   - Add new enum values to `NotificationType.java`
3. **New Services:**
   - `CheckInService.java`
   - `CheckInScheduler.java`
   - `ExifValidationService.java`
   - `GeofenceService.java`
   - `LockboxEncryptionService.java`
4. **New Controller:** `CheckInController.java`
5. **Security:**
   - Add `@bookingSecurity.isOwner()` and `@bookingSecurity.isRenter()` checks
   - Update `SecurityHeadersFilter.java` for geolocation permission
6. **Configuration:** Add to `application.yml`:
   ```yaml
   # ========== SERBIA REGIONAL SETTINGS ==========
   spring:
     jackson:
       time-zone: Europe/Belgrade
     jpa:
       properties:
         hibernate:
           jdbc:
             time_zone: Europe/Belgrade
   
   app:
     region:
       code: RS
       timezone: Europe/Belgrade
       locale: sr-Latn-RS
       currency: RSD
     checkin:
       scheduler:
         enabled: true
         window-cron: "0 0 * * * *"     # Evaluated in Europe/Belgrade
         noshow-cron: "0 0/10 * * * *"
       lockbox:
         encryption-key: ${LOCKBOX_ENCRYPTION_KEY}
       geofence:
         radius-meters: 100
       exif:
         max-age-hours: 24
         require-percentage: 50
       network:                          # Rural Serbia optimizations
         upload-timeout-seconds: 120
         chunk-size-bytes: 524288        # 512KB
         max-retries: 5
         offline-queue-ttl-hours: 72
       name-matching:
         algorithm: jaro-winkler
         threshold: 0.80
         serbian-normalization: true     # Enable Đ→Dj conversion
   ```

### 9.2 Frontend Changes

1. **Service Worker:** Extend with Background Sync handler
2. **ngsw-config.json:** Add check-in API caching
3. **New Services:**
   - `check-in.service.ts`
   - `check-in-offline.service.ts`
   - `geolocation.service.ts`
4. **New Components:**
   - `check-in-host.component.ts`
   - `check-in-guest.component.ts`
   - Photo grid, odometer input, fuel gauge, etc.
5. **Update `booking-details-dialog`:** Add "Start Check-in" button when status is `CHECK_IN_OPEN`
6. **Notifications:** Handle new notification types for check-in events

### 9.3 External Dependencies

| Dependency | Purpose | Priority | Serbia Notes |
|------------|---------|----------|---------------|
| Image EXIF library | `metadata-extractor` (Java) | P0 - Required | - |
| ID Verification API | Onfido/Veriff | P1 - Phase 2 | Must support Serbian ID cards (lična karta) |
| Cloud Storage | S3/GCS for photo storage | P1 - Phase 2 | EU region (Frankfurt) for GDPR |
| Push Notifications | Firebase FCM (already integrated) | P0 - Existing | - |
| Name Matching | Apache Commons Text (Jaro-Winkler) | P0 - Required | Serbian diacritic normalization |
| Timezone | Java 11+ `ZoneId` | P0 - Built-in | `Europe/Belgrade` only |

---

## 10. Rollout Strategy

### Phase 1: Core Check-in (MVP)
- State transitions (ACTIVE → CHECK_IN_OPEN → IN_TRIP)
- Host photo upload with EXIF validation
- Odometer/fuel capture
- Basic handshake (both swipe to confirm)
- Offline photo queue with sync
- No-show detection

### Phase 2: Remote Handoff (Turo Go)
- Lockbox code encryption/reveal
- Geofence validation
- GPS anti-spoofing

### Phase 3: Identity Verification
- ID document capture
- Liveness detection
- Name matching
- Document expiry validation

### Phase 4: Advanced Features
- Condition report with hotspot marking
- Pre-existing damage documentation
- Dispute resolution workflow

---

## 11. Metrics & Observability

```java
// Micrometer metrics to add

Counter.builder("checkin.window.opened")
    .description("Number of check-in windows opened")
    .register(registry);

Counter.builder("checkin.host.completed")
    .description("Host check-in completions")
    .register(registry);

Counter.builder("checkin.guest.completed")
    .description("Guest check-in completions")
    .register(registry);

Counter.builder("checkin.handshake.completed")
    .description("Successful trip starts")
    .register(registry);

Counter.builder("checkin.noshow")
    .tag("party", "host|guest")
    .description("No-show events")
    .register(registry);

Counter.builder("checkin.exif.rejected")
    .tag("reason", "too_old|no_exif|location_mismatch")
    .description("EXIF validation rejections")
    .register(registry);

Timer.builder("checkin.photo.upload.duration")
    .description("Photo upload latency")
    .register(registry);

Gauge.builder("checkin.offline.queue.size", offlineService, s -> s.getPendingCount())
    .description("Pending offline uploads")
    .register(registry);
```

---

## 12. Open Questions for Review

### Resolved (Serbia Context)

| Question | Resolution |
|----------|------------|
| Timezone handling? | **Single timezone: `Europe/Belgrade`** - no multi-tz complexity |
| Localization? | **Serbian Latin (sr-Latn-RS)** with UTF-8 support |
| Name matching for OCR? | **Serbian diacritic normalization** (Đ↔Dj, Ž↔Z, etc.) |
| Network timeout? | **120s upload timeout, 72h offline queue** for rural areas |

### Still Open

1. **ID Verification Provider:** Onfido vs Veriff - which supports Serbian ID cards (lična karta) better?
2. **Photo Storage:** S3 Frankfurt (eu-central-1) for GDPR compliance - self-managed or Cloudinary?
3. **EXIF Requirement:** 50% threshold acceptable for MVP?
4. **Geofence Radius:** 100m may be too restrictive for rural parking lots - increase to 200m?
5. **No-Show Grace Period:** 30 minutes standard for Serbia - sufficient for Belgrade traffic?
6. **Lockbox Code Length:** 4-10 digits - minimum for security?

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| **Handshake** | The final confirmation step where both parties agree to start the trip |
| **Geofence** | Virtual perimeter around the car's location for remote handoff |
| **Hotspot** | User-marked area on a photo indicating pre-existing damage |
| **Turo Go** | Turo's remote car access feature using lockbox/Bluetooth |
| **EXIF** | Exchangeable Image File Format - metadata embedded in photos |
| **Haversine** | Formula for calculating great-circle distance on a sphere |
| **Background Sync** | Service Worker API for deferring network requests until online |

### Serbian-Specific Terms

| Serbian | English | Context |
|---------|---------|----------|
| **Domaćin** | Host | Car owner |
| **Gost** | Guest | Renter |
| **Lična karta** | National ID Card | Primary ID document in Serbia |
| **Vozačka dozvola** | Driver's License | Required for check-in |
| **Kilometraža** | Odometer | Mileage reading |
| **Gorivo** | Fuel | Fuel level |
| **Prijem** | Check-in | Vehicle handover process |
| **Povratak** | Check-out | Vehicle return process |

---

**Document End**

*Next Step: After review approval, proceed to Phase 1 implementation starting with `V13__check_in_workflow.sql` and `BookingStatus.java` enum extension.*
