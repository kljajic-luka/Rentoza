# Availability Search Implementation Plan
**Date:** 2025-11-20
**Version:** 1.0
**Status:** Planning Phase

---

## Executive Summary

This document outlines the implementation plan for upgrading Rentoza's home page search from a location-only search to a comprehensive availability search supporting location + date range + time. The solution will enable users to find cars available within specific date/time windows while maintaining backward compatibility and security.

---

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Identified Gaps](#2-identified-gaps)
3. [Data Model Strategy](#3-data-model-strategy)
4. [API Design](#4-api-design)
5. [Security & Privacy Considerations](#5-security--privacy-considerations)
6. [Phased Implementation Plan](#6-phased-implementation-plan)
7. [Testing Strategy](#7-testing-strategy)
8. [Rollout & Monitoring](#8-rollout--monitoring)

---

## 1. Current State Analysis

### 1.1 Frontend Architecture

#### Home Component (`rentoza-frontend/src/app/features/home/pages/home/home.component.ts`)
**Current Behavior:**
- Single input field: "Gde vozite?" (Where are you driving?)
- Accepts location string only
- Navigation: `router.navigate(['/cars'], { queryParams: { location } })`
- No date/time inputs

**Key Files:**
- `home.component.ts` (lines 34-52): Search logic
- `home.component.html` (lines 15-37): Search UI with single mat-form-field

#### Car List Component (`rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`)
**Current Behavior:**
- Reads URL query params (location, minPrice, maxPrice, etc.)
- Calls `carService.searchCars(criteria)` with `CarSearchCriteria`
- **Does NOT** support date/time filtering

**Key Observation:**
- `CarSearchCriteria` interface (lines 100-114) includes: location, price, make, model, year, seats, transmission, features
- **Missing**: startDate, endDate, startTime, endTime fields

#### Car Service (`rentoza-frontend/src/app/core/services/car.service.ts`)
**Current Behavior:**
- `searchCars(criteria)` → `GET /api/cars/search` with query params (lines 74-159)
- Maps backend DTO (`brand` → `make`) correctly
- **No availability filtering** in current implementation

### 1.2 Backend Architecture

#### Booking Entity (`Rentoza/src/main/java/org/example/rentoza/booking/Booking.java`)
**Current Fields:**
```java
private LocalDate startDate;              // Line 27
private LocalDate endDate;                // Line 28
private String pickupTimeWindow;          // Line 39 (MORNING/AFTERNOON/EVENING/EXACT)
private LocalTime pickupTime;             // Line 42 (Optional, only for EXACT)
```

**Key Observations:**
- **Date-only** persistence (LocalDate, not LocalDateTime)
- Time captured via `pickupTimeWindow` enum + optional `pickupTime`
- No explicit `dropoffTime` field
- **Gap:** Cannot derive precise startDateTime/endDateTime without business logic

#### Car Entity (`Rentoza/src/main/java/org/example/rentoza/car/Car.java`)
**Current Fields:**
- `location` (String, indexed) → Line 58
- `available` (boolean, indexed) → Line 66
- Relations: `@OneToMany bookings` → Line 141

**Key Observation:**
- Location is simple string (not structured address/coordinates)
- Binary availability flag (does NOT reflect time-based availability)

#### BookingRepository (`Rentoza/src/main/java/org/example/rentoza/booking/BookingRepository.java`)
**Existing Overlap Detection:**
```java
@Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
       "AND b.status IN ('ACTIVE', 'COMPLETED') " +
       "AND b.startDate <= :endDate AND b.endDate >= :startDate")
boolean existsOverlappingBookings(@Param("carId") Long carId,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate);
```
**Lines:** 81-88

**Limitation:**
- Date-only overlap check
- Does NOT consider time of day
- Example: Car booked 9AM-12PM on 2025-01-15 would block entire day

#### CarController Search Endpoint (`Rentoza/src/main/java/org/example/rentoza/car/CarController.java`)
**Current Endpoint:**
```java
@GetMapping("/search")
public ResponseEntity<?> searchCars(
    @RequestParam(required = false) Double minPrice,
    @RequestParam(required = false) String location,
    // ... other filters
)
```
**Lines:** 78-150

**Missing Parameters:**
- `startDate`
- `endDate`
- `startTime`
- `endTime`

**Current Behavior:**
- Filters cars by static attributes only (price, location, transmission, etc.)
- Does NOT exclude cars with overlapping bookings

---

## 2. Identified Gaps

### 2.1 Data Model Gaps

| Gap | Current State | Required State |
|-----|---------------|----------------|
| **Time-aware bookings** | Booking has `startDate` (LocalDate) + `pickupTimeWindow` (String) | Need effective `startDateTime` and `endDateTime` |
| **Dropoff time** | No explicit field for dropoff time | Need default dropoff time or explicit field |
| **DateTime precision** | Date-only overlap detection | Need time-aware overlap checks (HH:mm precision) |

### 2.2 API Gaps

| Gap | Current Endpoint | Required Endpoint |
|-----|------------------|-------------------|
| **Availability search** | `GET /api/cars/search?location=X` | `GET /api/cars/availability-search?location=X&startDate=Y&startTime=Z&endDate=A&endTime=B` |
| **Time parameters** | None | `startDate`, `startTime`, `endDate`, `endTime` |
| **Overlap filtering** | Not implemented | Must exclude cars with conflicting bookings |

### 2.3 Frontend Gaps

| Gap | Current UI | Required UI |
|-----|-----------|-------------|
| **Home search** | Single text input (location) | Multi-field form: location + date range + time range |
| **Date pickers** | None | Material date picker components |
| **Time pickers** | None | Material time picker or select dropdowns |
| **Validation** | Basic (non-empty location) | End date/time must be after start date/time |

---

## 3. Data Model Strategy

### 3.1 Decision: Derive DateTime from Existing Fields (No Schema Changes)

**Rationale:**
- Minimize disruption to existing booking flow
- Avoid complex database migrations
- Maintain backward compatibility

**Approach:**
- **startDateTime** = `startDate` + derived time from `pickupTimeWindow`/`pickupTime`
- **endDateTime** = `endDate` + default dropoff time (configurable, e.g., 18:00)

### 3.2 Time Derivation Logic

#### Pickup Time Derivation
```java
public LocalDateTime derivePickupDateTime(Booking booking) {
    LocalDate startDate = booking.getStartDate();

    if ("EXACT".equals(booking.getPickupTimeWindow()) && booking.getPickupTime() != null) {
        return startDate.atTime(booking.getPickupTime());
    }

    // Map time windows to specific times
    LocalTime pickupTime = switch (booking.getPickupTimeWindow()) {
        case "MORNING" -> LocalTime.of(9, 0);    // 09:00
        case "AFTERNOON" -> LocalTime.of(14, 0); // 14:00
        case "EVENING" -> LocalTime.of(18, 0);   // 18:00
        default -> LocalTime.of(9, 0);           // Default to morning
    };

    return startDate.atTime(pickupTime);
}
```

#### Dropoff Time Derivation
```java
public LocalDateTime deriveDropoffDateTime(Booking booking) {
    LocalDate endDate = booking.getEndDate();

    // Default dropoff time (configurable via application.properties)
    LocalTime dropoffTime = LocalTime.of(18, 0); // 18:00 (6 PM)

    return endDate.atTime(dropoffTime);
}
```

**Configuration:**
```properties
# application.properties
app.booking.default-dropoff-time=18:00
app.booking.time-windows.morning=09:00
app.booking.time-windows.afternoon=14:00
app.booking.time-windows.evening=18:00
```

### 3.3 Time-Aware Overlap Detection

**New Repository Method:**
```java
/**
 * Check for time-aware overlapping bookings.
 * Considers pickup time window and dropoff time.
 *
 * Overlap Condition:
 * (requestedStartDateTime < booking.endDateTime) AND
 * (requestedEndDateTime > booking.startDateTime)
 */
boolean hasTimeAwareOverlap(
    Long carId,
    LocalDateTime requestedStartDateTime,
    LocalDateTime requestedEndDateTime
);
```

**Implementation Strategy:**
- Fetch all ACTIVE/COMPLETED bookings for car
- Derive effective startDateTime/endDateTime for each booking
- Check overlap using derived DateTimes
- **Note:** Not using JPQL for time derivation due to complexity; will handle in Java service layer

---

## 4. API Design

### 4.1 New Endpoint: Availability Search

**Endpoint:**
```
GET /api/cars/availability-search
```

**Request Parameters:**
```java
@RequestParam(required = true) String location
@RequestParam(required = true) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate
@RequestParam(required = true) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime
@RequestParam(required = true) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
@RequestParam(required = true) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime
@RequestParam(required = false, defaultValue = "0") Integer page
@RequestParam(required = false, defaultValue = "20") Integer size
@RequestParam(required = false) String sort
```

**Example Request:**
```
GET /api/cars/availability-search?location=beograd&startDate=2025-01-15&startTime=09:00&endDate=2025-01-17&endTime=18:00&page=0&size=20
```

**Validation Rules:**
1. All fields required
2. `endDate` must be >= `startDate`
3. If `endDate` == `startDate`, then `endTime` must be > `startTime` (minimum 1 hour rental)
4. `startDate` cannot be in the past (today is allowed)
5. Maximum search range: 90 days (configurable)

### 4.2 Request DTO

**New Class:** `AvailabilitySearchRequestDTO.java`

```java
package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilitySearchRequestDTO {
    private String location;
    private LocalDate startDate;
    private LocalTime startTime;
    private LocalDate endDate;
    private LocalTime endTime;
    private Integer page;
    private Integer size;
    private String sort;

    // Computed fields (not from request params)
    public LocalDateTime getStartDateTime() {
        return startDate.atTime(startTime);
    }

    public LocalDateTime getEndDateTime() {
        return endDate.atTime(endTime);
    }

    public void validate() {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Location is required");
        }
        if (startDate == null || startTime == null || endDate == null || endTime == null) {
            throw new IllegalArgumentException("All date and time fields are required");
        }
        if (getEndDateTime().isBefore(getStartDateTime())) {
            throw new IllegalArgumentException("End date/time must be after start date/time");
        }
        if (startDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Start date cannot be in the past");
        }
        // Minimum rental duration: 1 hour
        if (java.time.Duration.between(getStartDateTime(), getEndDateTime()).toHours() < 1) {
            throw new IllegalArgumentException("Minimum rental duration is 1 hour");
        }
    }
}
```

### 4.3 Response DTO

**Reuse Existing:** `CarResponseDTO.java`

**Why:**
- Already public-safe (no license plate, no sensitive owner info)
- Contains all necessary car information (brand, model, price, location, images, etc.)
- Supports pagination via Spring Data Page

**Response Structure:**
```json
{
  "content": [
    {
      "id": 1,
      "brand": "BMW",
      "model": "X5",
      "year": 2023,
      "pricePerDay": 150.0,
      "location": "beograd",
      "imageUrl": "...",
      "seats": 5,
      "fuelType": "DIZEL",
      "transmissionType": "AUTOMATIC",
      "available": true
    }
  ],
  "totalElements": 42,
  "totalPages": 3,
  "currentPage": 0,
  "pageSize": 20,
  "hasNext": true,
  "hasPrevious": false
}
```

---

## 5. Security & Privacy Considerations

### 5.1 Public-Safe Response Guarantees

**CarResponseDTO** already ensures:
- ✅ No `licensePlate` field
- ✅ No owner PII (name, email, phone)
- ✅ No renter details
- ✅ No booking IDs or references

**Additional Safeguards:**
- Availability search endpoint will be `@PreAuthorize("permitAll()")` (public access)
- Rate limiting: 60 requests/minute per IP (standard for search endpoints)
- No exposure of booking details (only "available" or "not available")

### 5.2 Authorization

**Endpoint Access:**
```java
@GetMapping("/availability-search")
@PreAuthorize("permitAll()") // Anonymous + authenticated users
public ResponseEntity<?> searchAvailableCars(...)
```

**Rationale:**
- Public search is standard for car rental platforms (Turo, Getaround, etc.)
- No sensitive data exposed
- Encourages discovery before signup

### 5.3 Input Validation & Abuse Prevention

**Protections:**
1. **Max Search Range:** Limit to 90 days to prevent expensive queries
2. **Pagination:** Default page size = 20, max = 100
3. **Location Normalization:** Lowercase + trim to prevent cache bypass
4. **Rate Limiting:** 60 req/min (configurable via Spring rate limiter)
5. **SQL Injection:** Spring Data parameterized queries
6. **XSS:** No user input echoed in responses (search params not returned in DTOs)

---

## 6. Phased Implementation Plan

### Phase 1: Backend Foundation (Days 1-3)

#### Milestone 1.1: Domain Model & Time Derivation Logic
**Files to Create/Modify:**
- `Rentoza/src/main/java/org/example/rentoza/booking/BookingTimeUtil.java` (NEW)
  - `derivePickupDateTime(Booking)` method
  - `deriveDropoffDateTime(Booking)` method
  - Unit tests for time derivation logic

**Files to Modify:**
- `Rentoza/src/main/resources/application.properties`
  - Add time window configurations

**Deliverables:**
- ✅ Time derivation utility class with comprehensive tests
- ✅ Configuration properties for default times

**Acceptance Criteria:**
- Pickup time correctly derived for all time windows (MORNING, AFTERNOON, EVENING, EXACT)
- Dropoff time defaults to configured value (18:00)
- Edge cases handled (null pickupTime for EXACT window → fallback to MORNING)

---

#### Milestone 1.2: Availability Service Layer
**Files to Create:**
- `Rentoza/src/main/java/org/example/rentoza/car/AvailabilityService.java` (NEW)
  - `searchAvailableCars(AvailabilitySearchRequestDTO)` method
  - `isCarAvailableInTimeRange(Car, LocalDateTime, LocalDateTime)` method

**Logic:**
```java
@Service
@RequiredArgsConstructor
public class AvailabilityService {
    private final CarRepository carRepository;
    private final BookingRepository bookingRepository;
    private final BookingTimeUtil bookingTimeUtil;

    public Page<Car> searchAvailableCars(AvailabilitySearchRequestDTO request) {
        // 1. Find cars by location (existing query)
        List<Car> carsInLocation = carRepository.findByLocationIgnoreCaseAndAvailableTrue(
            request.getLocation().toLowerCase().trim()
        );

        // 2. Filter by time-based availability
        List<Car> availableCars = carsInLocation.stream()
            .filter(car -> isCarAvailableInTimeRange(
                car,
                request.getStartDateTime(),
                request.getEndDateTime()
            ))
            .toList();

        // 3. Apply pagination
        return PageableExecutionUtils.getPage(
            availableCars,
            PageRequest.of(request.getPage(), request.getSize()),
            availableCars::size
        );
    }

    private boolean isCarAvailableInTimeRange(
        Car car,
        LocalDateTime requestedStart,
        LocalDateTime requestedEnd
    ) {
        // Fetch all active/completed bookings for this car
        List<Booking> bookings = bookingRepository.findPublicBookingsForCar(car.getId());

        // Check for time-aware overlaps
        return bookings.stream().noneMatch(booking -> {
            LocalDateTime bookingStart = bookingTimeUtil.derivePickupDateTime(booking);
            LocalDateTime bookingEnd = bookingTimeUtil.deriveDropoffDateTime(booking);

            // Overlap condition: (requestedStart < bookingEnd) AND (requestedEnd > bookingStart)
            return requestedStart.isBefore(bookingEnd) && requestedEnd.isAfter(bookingStart);
        });
    }
}
```

**Deliverables:**
- ✅ AvailabilityService with search logic
- ✅ Unit tests for overlap detection (10+ test cases)
- ✅ Integration tests with mocked repositories

**Test Cases:**
1. No bookings → car available
2. Booking before requested range → car available
3. Booking after requested range → car available
4. Booking overlaps start of requested range → car NOT available
5. Booking overlaps end of requested range → car NOT available
6. Booking fully within requested range → car NOT available
7. Requested range fully within booking → car NOT available
8. Same-day booking (morning vs afternoon time windows) → car available if non-overlapping
9. Multiple bookings → car NOT available if any overlap
10. Edge case: booking ends exactly when request starts → car available (no overlap)

---

#### Milestone 1.3: API Controller & DTOs
**Files to Create:**
- `Rentoza/src/main/java/org/example/rentoza/car/dto/AvailabilitySearchRequestDTO.java` (NEW)

**Files to Modify:**
- `Rentoza/src/main/java/org/example/rentoza/car/CarController.java`
  - Add `@GetMapping("/availability-search")` endpoint

**Controller Method:**
```java
@GetMapping("/availability-search")
@PreAuthorize("permitAll()")
public ResponseEntity<?> searchAvailableCars(
    @RequestParam String location,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
    @RequestParam(required = false, defaultValue = "0") Integer page,
    @RequestParam(required = false, defaultValue = "20") Integer size,
    @RequestParam(required = false) String sort
) {
    try {
        // Build request DTO
        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
            .location(location)
            .startDate(startDate)
            .startTime(startTime)
            .endDate(endDate)
            .endTime(endTime)
            .page(page)
            .size(size)
            .sort(sort)
            .build();

        // Validate request
        request.validate();

        // Execute search
        Page<Car> results = availabilityService.searchAvailableCars(request);

        // Map to response DTOs
        Page<CarResponseDTO> responseDTOs = results.map(CarResponseDTO::new);

        // Return paginated response
        return ResponseEntity.ok(Map.of(
            "content", responseDTOs.getContent(),
            "totalElements", responseDTOs.getTotalElements(),
            "totalPages", responseDTOs.getTotalPages(),
            "currentPage", responseDTOs.getNumber(),
            "pageSize", responseDTOs.getSize(),
            "hasNext", responseDTOs.hasNext(),
            "hasPrevious", responseDTOs.hasPrevious()
        ));

    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
        log.error("Error in availability search", e);
        return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
    }
}
```

**Deliverables:**
- ✅ AvailabilitySearchRequestDTO with validation
- ✅ New endpoint in CarController
- ✅ Integration tests (Postman/REST Assured)
- ✅ API documentation (OpenAPI/Swagger annotations)

**Acceptance Criteria:**
- Endpoint returns 200 with paginated results for valid requests
- Endpoint returns 400 for invalid date ranges
- Endpoint returns 400 for past dates
- Endpoint correctly filters cars with overlapping bookings
- Pagination works correctly

---

### Phase 2: Frontend Home Page UI (Days 4-5)

#### Milestone 2.1: Multi-Field Search UI
**Files to Modify:**
- `rentoza-frontend/src/app/features/home/pages/home/home.component.ts`
- `rentoza-frontend/src/app/features/home/pages/home/home.component.html`
- `rentoza-frontend/src/app/features/home/pages/home/home.component.scss`

**New UI Structure:**
```html
<div class="hero__search-row">
  <!-- Location Input -->
  <mat-form-field appearance="outline" class="hero__search-input">
    <mat-label>Gde vozite?</mat-label>
    <input matInput placeholder="Beograd, Novi Sad..." [(ngModel)]="searchLocation" />
    <mat-icon matPrefix>location_on</mat-icon>
  </mat-form-field>

  <!-- Start Date -->
  <mat-form-field appearance="outline" class="hero__search-input">
    <mat-label>Od datuma</mat-label>
    <input matInput [matDatepicker]="startPicker" [(ngModel)]="searchStartDate" />
    <mat-datepicker-toggle matSuffix [for]="startPicker"></mat-datepicker-toggle>
    <mat-datepicker #startPicker></mat-datepicker>
  </mat-form-field>

  <!-- Start Time -->
  <mat-form-field appearance="outline" class="hero__search-input">
    <mat-label>Od vremena</mat-label>
    <mat-select [(ngModel)]="searchStartTime">
      <mat-option *ngFor="let time of timeOptions" [value]="time.value">{{ time.label }}</mat-option>
    </mat-select>
  </mat-form-field>

  <!-- End Date -->
  <mat-form-field appearance="outline" class="hero__search-input">
    <mat-label>Do datuma</mat-label>
    <input matInput [matDatepicker]="endPicker" [(ngModel)]="searchEndDate" />
    <mat-datepicker-toggle matSuffix [for]="endPicker"></mat-datepicker-toggle>
    <mat-datepicker #endPicker></mat-datepicker>
  </mat-form-field>

  <!-- End Time -->
  <mat-form-field appearance="outline" class="hero__search-input">
    <mat-label>Do vremena</mat-label>
    <mat-select [(ngModel)]="searchEndTime">
      <mat-option *ngFor="let time of timeOptions" [value]="time.value">{{ time.label }}</mat-option>
    </mat-select>
  </mat-form-field>

  <!-- Search Button -->
  <button mat-raised-button color="primary" class="hero__search-cta" (click)="searchCars()">
    <mat-icon>search</mat-icon>
    <span>Pretraži</span>
  </button>
</div>
```

**Component Logic:**
```typescript
export class HomeComponent {
  searchLocation = '';
  searchStartDate: Date | null = null;
  searchStartTime = '09:00';
  searchEndDate: Date | null = null;
  searchEndTime = '18:00';

  readonly timeOptions = [
    { value: '06:00', label: '06:00' },
    { value: '07:00', label: '07:00' },
    { value: '08:00', label: '08:00' },
    { value: '09:00', label: '09:00' },
    { value: '10:00', label: '10:00' },
    { value: '11:00', label: '11:00' },
    { value: '12:00', label: '12:00' },
    { value: '13:00', label: '13:00' },
    { value: '14:00', label: '14:00' },
    { value: '15:00', label: '15:00' },
    { value: '16:00', label: '16:00' },
    { value: '17:00', label: '17:00' },
    { value: '18:00', label: '18:00' },
    { value: '19:00', label: '19:00' },
    { value: '20:00', label: '20:00' },
    { value: '21:00', label: '21:00' },
    { value: '22:00', label: '22:00' },
  ];

  searchCars(): void {
    // Validation
    if (!this.searchLocation.trim()) {
      this.snackBar.open('Unesite lokaciju', 'Zatvori', { duration: 3000 });
      return;
    }
    if (!this.searchStartDate || !this.searchEndDate) {
      this.snackBar.open('Izaberite datume', 'Zatvori', { duration: 3000 });
      return;
    }

    const startDateTime = this.combineDateAndTime(this.searchStartDate, this.searchStartTime);
    const endDateTime = this.combineDateAndTime(this.searchEndDate, this.searchEndTime);

    if (endDateTime <= startDateTime) {
      this.snackBar.open('Kraj mora biti posle početka', 'Zatvori', { duration: 3000 });
      return;
    }

    // Navigate to search results with availability params
    this.router.navigate(['/cars'], {
      queryParams: {
        location: this.searchLocation.trim(),
        startDate: this.formatDate(this.searchStartDate),
        startTime: this.searchStartTime,
        endDate: this.formatDate(this.searchEndDate),
        endTime: this.searchEndTime,
        availabilitySearch: 'true' // Flag to trigger availability API
      }
    });
  }

  private combineDateAndTime(date: Date, time: string): Date {
    const [hours, minutes] = time.split(':').map(Number);
    const combined = new Date(date);
    combined.setHours(hours, minutes, 0, 0);
    return combined;
  }

  private formatDate(date: Date): string {
    return date.toISOString().split('T')[0]; // YYYY-MM-DD
  }
}
```

**Deliverables:**
- ✅ Multi-field search form with date/time pickers
- ✅ Client-side validation
- ✅ Responsive layout (desktop + mobile)
- ✅ Material Design 3 styling
- ✅ Dark mode support

**Acceptance Criteria:**
- All fields required and validated
- End date/time must be after start date/time
- Smooth navigation to car list with query params
- Mobile-friendly layout (vertical stack on small screens)

---

#### Milestone 2.2: Frontend Service Integration
**Files to Modify:**
- `rentoza-frontend/src/app/core/services/car.service.ts`
- `rentoza-frontend/src/app/core/models/car-search.model.ts`

**Add to CarService:**
```typescript
/**
 * Search cars by availability (location + date/time range)
 * @param location Location string
 * @param startDate Start date (YYYY-MM-DD)
 * @param startTime Start time (HH:mm)
 * @param endDate End date (YYYY-MM-DD)
 * @param endTime End time (HH:mm)
 * @param page Page number (default: 0)
 * @param size Page size (default: 20)
 */
searchAvailableCars(
  location: string,
  startDate: string,
  startTime: string,
  endDate: string,
  endTime: string,
  page: number = 0,
  size: number = 20
): Observable<PagedResponse<Car>> {
  let params = new HttpParams()
    .set('location', location)
    .set('startDate', startDate)
    .set('startTime', startTime)
    .set('endDate', endDate)
    .set('endTime', endTime)
    .set('page', page.toString())
    .set('size', size.toString());

  return this.http.get<any>(`${this.baseUrl}/availability-search`, { params }).pipe(
    map((response) => ({
      content: response.content.map((car: any) => this.mapBackendCarToFrontend(car)),
      totalElements: response.totalElements,
      totalPages: response.totalPages,
      currentPage: response.currentPage,
      pageSize: response.pageSize,
      hasNext: response.hasNext,
      hasPrevious: response.hasPrevious,
    }))
  );
}
```

**Deliverables:**
- ✅ New method in CarService
- ✅ HTTP parameter mapping
- ✅ Error handling

---

### Phase 3: Car List Integration (Days 6-7)

#### Milestone 3.1: Availability Search Mode
**Files to Modify:**
- `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Changes:**
1. Detect `availabilitySearch=true` query param
2. Read date/time params from URL
3. Call `carService.searchAvailableCars()` instead of `carService.searchCars()`
4. Display active availability filters as chips

**Implementation:**
```typescript
ngOnInit(): void {
  this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
    const isAvailabilitySearch = params.get('availabilitySearch') === 'true';

    if (isAvailabilitySearch) {
      // Availability search mode
      const location = params.get('location') || '';
      const startDate = params.get('startDate') || '';
      const startTime = params.get('startTime') || '09:00';
      const endDate = params.get('endDate') || '';
      const endTime = params.get('endTime') || '18:00';
      const page = params.get('page') ? Number(params.get('page')) : 0;
      const size = params.get('size') ? Number(params.get('size')) : 20;

      this.availabilitySearchResults$ = this.carService.searchAvailableCars(
        location, startDate, startTime, endDate, endTime, page, size
      );

      this.displayAvailabilityFilters(location, startDate, startTime, endDate, endTime);
    } else {
      // Standard search mode (existing logic)
      const criteria: CarSearchCriteria = { /* ... existing code ... */ };
      this.searchCriteria$.next(criteria);
    }
  });
}
```

**Deliverables:**
- ✅ Availability search mode detection
- ✅ Conditional API call logic
- ✅ Display active filters (location + date/time range)

**Acceptance Criteria:**
- Car list shows only available cars for requested date/time range
- Active filters displayed as chips
- Pagination works correctly
- "No results" state shown if no cars available

---

### Phase 4: Edge Cases & Validation (Days 8-9)

#### Milestone 4.1: Backend Edge Case Handling
**Test Cases:**
1. **Past dates:** 400 Bad Request
2. **Invalid time format:** 400 Bad Request
3. **Start > End:** 400 Bad Request
4. **Same day, end time before start time:** 400 Bad Request
5. **Rental duration < 1 hour:** 400 Bad Request
6. **Search range > 90 days:** 400 Bad Request
7. **Empty location:** 400 Bad Request
8. **SQL injection attempt:** Parameterized query prevents
9. **XSS in location:** No echo in response
10. **Rate limit exceeded:** 429 Too Many Requests

**Files to Add Tests:**
- `Rentoza/src/test/java/org/example/rentoza/car/AvailabilityServiceTest.java`
- `Rentoza/src/test/java/org/example/rentoza/car/CarControllerTest.java`

---

#### Milestone 4.2: Frontend UX Enhancements
**Features:**
1. **Loading spinner** during search
2. **Empty state** if no results: "Nema dostupnih automobila za ove datume. Pokušajte druge datume ili lokaciju."
3. **Error handling** for 400/500 responses
4. **Validation hints** below input fields
5. **Min date restriction** on date pickers (today)
6. **Smart defaults:**
   - Start date: tomorrow
   - Start time: 09:00
   - End date: tomorrow + 2 days
   - End time: 18:00

---

### Phase 5: Documentation & Testing (Day 10)

#### Milestone 5.1: API Documentation
**Files to Update:**
- `Rentoza/README.md` (or create API_DOCS.md)
- Add OpenAPI/Swagger annotations to endpoint

**Documentation Sections:**
1. Endpoint description
2. Request parameters with examples
3. Response structure with examples
4. Error codes (400, 429, 500)
5. Rate limiting policy
6. Usage examples (curl, Postman)

---

#### Milestone 5.2: Integration Testing
**Test Scenarios:**
1. End-to-end: Home → Search → Results → Car Details → Booking
2. Multiple users searching same car (concurrency)
3. Booking conflict detection (two users book same time slot)
4. Mobile responsive testing
5. Dark mode testing
6. Performance testing (100 concurrent searches)

---

## 7. Testing Strategy

### 7.1 Unit Tests

**Backend (JUnit 5 + Mockito):**
- `BookingTimeUtilTest.java`: Time derivation logic (10+ test cases)
- `AvailabilityServiceTest.java`: Overlap detection (15+ test cases)
- `AvailabilitySearchRequestDTOTest.java`: Validation logic (8+ test cases)

**Frontend (Jasmine + Karma):**
- `home.component.spec.ts`: Search form validation (6+ test cases)
- `car.service.spec.ts`: API call mocking (4+ test cases)
- `car-list.component.spec.ts`: Availability mode switching (5+ test cases)

### 7.2 Integration Tests

**Backend (Spring Boot Test + TestRestTemplate):**
- `AvailabilitySearchIntegrationTest.java`:
  - Happy path: location + dates → filtered results
  - No availability: all cars booked → empty results
  - Partial overlap: some cars available → subset results
  - Invalid input: 400 responses
  - Rate limiting: 429 responses

**Frontend (Cypress/Playwright E2E):**
- `availability-search.e2e.ts`:
  - User enters search params → navigates to results
  - Invalid date range → error message displayed
  - No results → empty state shown
  - Pagination works correctly

### 7.3 Performance Tests

**Load Testing (JMeter/Gatling):**
- 100 concurrent users searching
- Average response time < 500ms
- 95th percentile < 1s
- Zero errors under normal load

**Database Query Optimization:**
- Use EXPLAIN ANALYZE on overlap detection queries
- Ensure indexes exist on `cars.location` and `bookings.start_date`/`end_date`
- Monitor query execution time (target: < 100ms per query)

---

## 8. Rollout & Monitoring

### 8.1 Deployment Strategy

**Staged Rollout:**
1. **Dev environment:** Full testing by dev team (Day 11)
2. **Staging environment:** QA + UAT (Day 12-13)
3. **Production canary:** 10% traffic (Day 14)
4. **Production full:** 100% traffic (Day 15)

**Rollback Plan:**
- Feature flag: `app.features.availability-search.enabled=true`
- If issues detected, set flag to `false` to disable new endpoint
- Fallback to standard search on frontend

### 8.2 Monitoring & Metrics

**Backend Metrics:**
- Endpoint latency (p50, p95, p99)
- Error rate (4xx, 5xx)
- Search result counts (empty vs populated)
- Database query performance

**Frontend Metrics:**
- Page load time
- Search form completion rate
- Navigation success rate (home → results)
- User engagement (searches per session)

**Alerts:**
- Endpoint latency > 1s (P95)
- Error rate > 5%
- Zero results rate > 80% (indicates data/logic issue)

---

## 9. Future Enhancements (Post-MVP)

### 9.1 Advanced Features
1. **Map-based search:** Show cars on interactive map with availability
2. **Flexible time windows:** "I'm flexible" option for ±2 days
3. **Price filtering:** Combine availability + price range
4. **Instant book filter:** Show only cars with instant approval
5. **Multi-location search:** "Beograd or Novi Sad"

### 9.2 Optimization Opportunities
1. **Caching:** Redis cache for popular location + date/time combos (1-hour TTL)
2. **Database optimization:** Materialized view for booking availability
3. **Elasticsearch integration:** Full-text search on location + metadata
4. **Async processing:** Background job for complex searches (> 100 cars)

---

## 10. Risk Analysis

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| **Time derivation bugs** | Medium | High | Comprehensive unit tests (10+ cases) |
| **Performance degradation** | Low | High | Load testing + query optimization |
| **Backend breaking changes** | Low | High | Feature flag + rollback plan |
| **Frontend validation bypass** | Low | Medium | Server-side validation (defense in depth) |
| **Rate limit abuse** | Medium | Low | Spring rate limiter + monitoring |
| **Empty results UX** | Medium | Medium | Clear empty state with helpful suggestions |

---

## 11. Success Criteria

### MVP Definition of Done
- ✅ Home page has 5 input fields (location + date range + time range)
- ✅ Backend API returns only available cars for requested time window
- ✅ No security regressions (license plate not exposed)
- ✅ All unit tests passing (95%+ code coverage)
- ✅ Integration tests passing (happy path + edge cases)
- ✅ Performance SLA met (p95 < 1s)
- ✅ Mobile responsive design
- ✅ Dark mode support

### Post-MVP Metrics (30 days after launch)
- Search conversion rate > 40% (searches → bookings)
- Average search result count: 5-15 cars (not too few, not overwhelming)
- User feedback score > 4.5/5
- Zero critical bugs reported
- Availability accuracy: 99%+ (no double-bookings)

---

## 12. Appendix

### A. File Path Reference

**Backend Files:**
```
Rentoza/src/main/java/org/example/rentoza/
├── booking/
│   ├── Booking.java (existing)
│   ├── BookingRepository.java (existing)
│   ├── BookingTimeUtil.java (NEW)
├── car/
│   ├── Car.java (existing)
│   ├── CarRepository.java (existing)
│   ├── CarController.java (modify)
│   ├── AvailabilityService.java (NEW)
│   └── dto/
│       ├── CarResponseDTO.java (existing)
│       └── AvailabilitySearchRequestDTO.java (NEW)
```

**Frontend Files:**
```
rentoza-frontend/src/app/
├── features/
│   ├── home/pages/home/
│   │   ├── home.component.ts (modify)
│   │   ├── home.component.html (modify)
│   │   └── home.component.scss (modify)
│   └── cars/pages/car-list/
│       └── car-list.component.ts (modify)
├── core/
│   ├── services/
│   │   └── car.service.ts (modify)
│   └── models/
│       └── car-search.model.ts (modify - optional)
```

---

**End of Plan v1.0**
