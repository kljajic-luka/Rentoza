# Check-In Handshake Protocol - Phase 2 Implementation Complete

**Date:** November 29, 2025
**Status:** ✅ Complete (with Architectural Refinements)

## Overview

Phase 2 implements the **Service Layer & REST API** for the Turo-style check-in workflow. This phase builds upon the database schema created in Phase 1 and provides all business logic for the host-guest vehicle handoff process.

## Architectural Refinements Applied

### 1. Basement Problem Fix (EXIF Validation)
**Problem:** Original 30-minute max-age was too aggressive. Photos taken in parking garages with no signal would be rejected when uploaded 35 minutes later.

**Solution:**
- Increased `max-age-minutes` to **120 minutes** (2 hours)
- Compare EXIF timestamp against **client upload start time** (not server receipt time)
- Added `clientTimestampToleranceSeconds=300` for clock drift

### 2. Serbian 4G Risk Mitigation (Photo Config)
**Problem:** 10MB × 15 photos = 150MB payload. Edge/3G on Tara or Stara Planina would timeout.

**Solution:**
- Backend: Keep 10MB limit as safety net
- **Frontend Mandate (Phase 3):** Must compress images to <500KB before upload
- Added `frontend-target-size=512000` config for documentation

### 3. GPS Drift Fix (Dynamic Geofence)
**Problem:** Urban canyons in Belgrade (New Belgrade high-rises, Vračar narrow streets) cause 50m+ GPS multipath interference.

**Solution:**
- Implemented **dynamic radius** based on location density:
  - **Urban:** 150m (high-rise areas, GPS multipath)
  - **Suburban:** 100m (residential)
  - **Rural:** 50m (open areas, better accuracy)
- Auto-infers density from coordinates (Belgrade, Novi Sad, Niš recognized as urban)
- `strict=false` remains MVP default to avoid blocking legitimate users

## Files Created

### 1. Scheduler
| File | Purpose |
|------|---------|
| `CheckInScheduler.java` | Cron jobs for T-24h window opening, T-12h reminders, T+30m no-show detection |

### 2. Repositories
| File | Purpose |
|------|---------|
| `CheckInEventRepository.java` | Audit event queries (by session, booking, actor) |
| `CheckInPhotoRepository.java` | Photo queries with soft-delete and EXIF filtering |

### 3. Services
| File | Purpose |
|------|---------|
| `CheckInEventService.java` | Create immutable audit events with HTTP context (IP, User-Agent) |
| `ExifValidationService.java` | EXIF extraction, timestamp/GPS validation using Apache Commons Imaging |
| `GeofenceService.java` | Haversine distance calculation for proximity validation |
| `CheckInService.java` | Core orchestrator (~500 lines) - manages entire workflow |
| `CheckInPhotoService.java` | Photo upload, async EXIF validation, storage management |

### 4. DTOs
| File | Purpose |
|------|---------|
| `HostCheckInSubmissionDTO.java` | Host submission with odometer, fuel, photos, lockbox code |
| `GuestConditionAcknowledgmentDTO.java` | Guest acknowledgment with hotspot markings |
| `HotspotMarkingDTO.java` | Pre-existing damage notation |
| `HandshakeConfirmationDTO.java` | GPS-validated handshake confirmation |
| `CheckInStatusDTO.java` | Complete status response with photos, events |
| `CheckInPhotoDTO.java` | Photo response with EXIF validation status |

### 5. Controller
| File | Purpose |
|------|---------|
| `CheckInController.java` | REST API with Micrometer metrics, exception handling |

## Files Modified

| File | Changes |
|------|---------|
| `ExifValidationStatus.java` | Added `VALID_NO_GPS`, `VALID_WITH_WARNINGS`, `REJECTED_NO_GPS` |
| `BookingRepository.java` | Added 5 check-in specific queries |
| `NotificationType.java` | Added 8 check-in notification types |
| `application-dev.properties` | Added complete `app.checkin.*` configuration |

## API Endpoints

```
GET  /api/bookings/{bookingId}/check-in/status          - Get check-in status
POST /api/bookings/{bookingId}/check-in/host/photos     - Upload host photo
POST /api/bookings/{bookingId}/check-in/host/complete   - Complete host check-in
POST /api/bookings/{bookingId}/check-in/guest/condition-ack - Guest acknowledges
POST /api/bookings/{bookingId}/check-in/handshake       - Confirm handshake
GET  /api/bookings/{bookingId}/check-in/lockbox-code    - Reveal lockbox code (guest)
```

## Configuration Properties

```properties
# Scheduler
app.checkin.scheduler.enabled=true
app.checkin.window-hours-before-trip=24
app.checkin.reminder-hours-before-trip=12
app.checkin.no-show-minutes-after-trip-start=30

# EXIF Validation (Basement Problem Fix)
app.checkin.exif.max-age-minutes=120          # 2 hours, not 30 min
app.checkin.exif.client-timestamp-tolerance-seconds=300
app.checkin.exif.max-distance-meters=1000
app.checkin.exif.require-gps=false

# Geofence (Dynamic Radius)
app.checkin.geofence.threshold-meters=100     # Default fallback
app.checkin.geofence.radius-urban-meters=150  # Belgrade high-rises
app.checkin.geofence.radius-suburban-meters=100
app.checkin.geofence.radius-rural-meters=50
app.checkin.geofence.dynamic-radius-enabled=true
app.checkin.geofence.strict=false

# Photos (Frontend Compression Mandate)
app.checkin.photos.directory=check-in-photos
app.checkin.photos.max-size=10485760          # 10MB safety net
app.checkin.photos.frontend-target-size=512000 # 500KB frontend target
app.checkin.photos.allowed-types=image/jpeg,image/png,image/heic
```

## Notification Types Added

| Type | Trigger |
|------|---------|
| `CHECK_IN_WINDOW_OPENED` | T-24h before trip start |
| `CHECK_IN_REMINDER` | T-12h if check-in incomplete |
| `CHECK_IN_HOST_COMPLETE` | Host submits vehicle condition |
| `TRIP_STARTED` | Both parties confirm handshake |
| `NO_SHOW_HOST` | Host fails to submit by T+30m |
| `NO_SHOW_GUEST` | Guest fails to acknowledge by T+30m |
| `HOTSPOT_MARKED` | Guest marks pre-existing damage |
| `HANDSHAKE_CONFIRMED` | Successful handover complete |

## Workflow State Machine

```
CONFIRMED → CHECK_IN_OPEN → HOST_SUBMITTED → GUEST_ACKNOWLEDGED → HANDSHAKE_PENDING → TRIP_ACTIVE
                ↓                                                        ↓
           NO_SHOW_HOST                                            NO_SHOW_GUEST
```

## Key Features Implemented

### EXIF Validation (Basement Problem Solved)
- Extracts timestamp and GPS from photo metadata
- Compares EXIF timestamp against **client upload start time** (not server)
- 120-minute max age handles garage/basement scenarios
- Detects screenshot attempts (no EXIF data)
- 300-second tolerance for device clock drift

### Geofence Validation (GPS Drift Solved)
- Haversine formula for GPS distance calculation
- **Dynamic radius** based on inferred location density:
  - Urban (Belgrade, Novi Sad, Niš): 150m
  - Suburban: 100m
  - Rural: 50m
- Strict mode toggle for remote lockbox pickup
- Audit logging includes density and dynamic radius info

### Audit Trail
- Immutable `CheckInEvent` records for all actions
- Captures IP address, User-Agent, device fingerprint
- Event correlation via `checkInSessionId`
- **Geofence events** now include location density and radius applied
- Legal evidence for dispute resolution

### Scheduled Tasks
- Every minute: Check for bookings entering T-24h window
- Hourly: Send reminders to parties with incomplete check-in
- Every 5 minutes: Detect no-show violations after T+30m

## Metrics (Micrometer)

| Metric | Type | Description |
|--------|------|-------------|
| `checkin.window.opened` | Counter | Windows opened by scheduler |
| `checkin.reminder.sent` | Counter | Reminders sent |
| `checkin.noshow.detected` | Counter | No-shows detected |
| `checkin.host.submit` | Timer | Host submission latency |
| `checkin.guest.acknowledge` | Timer | Guest acknowledgment latency |
| `checkin.handshake.confirm` | Timer | Handshake confirmation latency |
| `checkin.photo.upload` | Counter | Photo uploads |

## Compilation Status

✅ **Check-in package compiles successfully**

Note: Pre-existing compilation errors exist in `admin/AdminUserController.java` and `admin/dto/UserModerationResponseDTO.java` - these are unrelated to the check-in implementation.

## Next Steps (Phase 3)

1. **Frontend Integration**
   - Angular components for check-in workflow
   - Photo capture with camera access
   - **CRITICAL: Implement client-side image compression to <500KB**
   - Pass `clientTimestamp` with each photo upload request
   - GPS acquisition for geofence validation

2. **Testing**
   - Unit tests for services
   - Integration tests for workflows
   - E2E tests with Playwright
   - **Test edge cases:** basement uploads, urban GPS drift

3. **Documentation**
   - API documentation (Swagger/OpenAPI)
   - User guides for hosts and guests

---

## Technical Debt Addressed

| Issue | Risk | Solution |
|-------|------|----------|
| 30-min EXIF limit | Basement uploads rejected | 120-min limit + client timestamp |
| 10MB photos | 150MB payload timeout | Frontend compression mandate |
| Fixed 100m geofence | Urban GPS drift false positives | Dynamic radius by location |
