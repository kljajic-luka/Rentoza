# Owner Public Profile & Guest Preview Implementation Plan

## 1. Executive Summary
This document outlines the implementation plan for two key features:
1.  **Guest Booking Preview (Refinement)**: Enhancing the existing implementation to meet "Elite" architectural standards, specifically adding missing fields (`badges`) and ensuring strict security controls.
2.  **Owner Public Profile (New Feature)**: Creating a public-facing profile page for car owners, allowing prospective renters to view host details, statistics, reviews, and their fleet of cars.

## 2. Guest Booking Preview Refinement

### 2.1 Backend Enhancements
**Current State**: `GuestBookingPreviewDTO` and `GuestBookingMapper` exist but lack the `badges` field required by the new mandate.

**Required Changes**:
*   **DTO Update**: Add `List<String> badges` to `GuestBookingPreviewDTO`.
*   **Mapper Update**: Implement logic in `GuestBookingMapper` to populate `badges`.
    *   *Logic*: If `tripCount > 10` -> "Experienced Guest". If `emailVerified` & `phoneVerified` & `identityVerified` -> "Verified Identity".
*   **Service Layer**: Ensure `BookingService.getGuestPreview` correctly populates these fields.

### 2.2 Frontend Verification
*   **Component**: `GuestBookingPreviewDialogComponent`.
*   **Task**: Verify the dialog correctly displays the new `badges` field and maintains the "Dark Mode" fix.

## 3. Owner Public Profile Implementation

### 3.1 Backend Architecture

#### DTOs
*   **`OwnerPublicProfileDTO`**:
    *   `Long id`
    *   `String firstName`
    *   `String lastName` (or initial if privacy required, but usually full name for public profiles)
    *   `String avatarUrl`
    *   `String joinDate` (e.g., "Joined Oct 2021")
    *   `String about` (Bio)
    *   `Double averageRating`
    *   `int totalTrips`
    *   `String responseTime` (e.g., "1 hour")
    *   `String responseRate` (e.g., "100%")
    *   `List<ReviewPreviewDTO> recentReviews`
    *   `List<OwnerCarPreviewDTO> cars`
    *   `boolean isSuperHost` (Badge logic)

*   **`OwnerCarPreviewDTO`**:
    *   `Long id`
    *   `String brand`
    *   `String model`
    *   `int year`
    *   `String imageUrl`
    *   `Double pricePerDay`
    *   `Double rating`
    *   `int tripCount`

#### Controller & Service
*   **Controller**: `OwnerProfileController` (New)
    *   Endpoint: `GET /api/owners/{id}/public-profile`
    *   Security: `@PreAuthorize("permitAll()")` (Public access)
    *   Cache Control: Short-term caching allowed (e.g., 5 mins) as data is public.
*   **Service**: `OwnerProfileService` (New)
    *   Logic: Fetch user by ID, validate they are an owner (have cars or `OWNER` role).
    *   Fetch active cars (exclude disabled/hidden).
    *   Calculate stats (rating, trips).

### 3.2 Frontend Architecture

#### Components
*   **`OwnerProfilePageComponent`** (Standalone):
    *   **Header**: Avatar, Name, "Superhost" badge, Join Date.
    *   **Stats Bar**: Rating, Trips, Response Time.
    *   **About Section**: Text bio.
    *   **Car Fleet Grid**: Grid of `OwnerCarPreviewDTO` cards.
    *   **Reviews Section**: List of recent reviews.

#### Routing
*   Path: `owners/:id`
*   Lazy loaded in `app.routes.ts`.

## 4. Implementation Steps

### Phase 1: Guest Preview Refinement
1.  Modify `GuestBookingPreviewDTO.java` to add `badges`.
2.  Update `GuestBookingMapper.java` to populate `badges`.
3.  Verify `BookingService.java` integration.

### Phase 2: Owner Profile Backend
1.  Create `OwnerCarPreviewDTO.java`.
2.  Create `OwnerPublicProfileDTO.java`.
3.  Create `OwnerProfileService.java`.
4.  Create `OwnerProfileController.java`.

### Phase 3: Owner Profile Frontend
1.  Generate `OwnerProfilePageComponent`.
2.  Create `OwnerProfileService` (Angular).
3.  Implement UI with Material Design and Signals.
4.  Configure Routing.

## 5. Security & Performance
*   **RLS**:
    *   Guest Preview: Strict RLS (Owner Only). `Cache-Control: no-store`.
    *   Owner Profile: Public. RLS allows read access to specific fields only.
*   **Data Minimization**: DTOs strictly define what is exposed. No entity leakage.
*   **Performance**:
    *   Owner Profile: Database indexing on `owner_id` for cars and reviews.
    *   Pagination for reviews if list is long (initial implementation: top 5).
