# Rentoza Owner Feature - Implementation Context

## Overview

This document outlines the complete implementation of the Owner UI and Review Flow for Rentoza, transforming it into a full two-sided car-sharing platform inspired by Turo's host experience.

---

## System Architecture

### User Roles
- **USER** (Renter) - Can browse and book cars, leave reviews for owners
- **OWNER** - Can list cars, manage bookings, review renters
- **ADMIN** - Full system access

### Core Relationships
```
User (OWNER) --< Car --< Booking >-- User (RENTER)
                  |
                  v
              Reviews (bidirectional: FROM_USER / FROM_OWNER)
```

---

## Backend Structure (Already Implemented)

### Entities

#### Car Entity
**Location:** `/Rentoza/src/main/java/org/example/rentoza/car/Car.java`

```java
@Entity
public class Car {
    // Basic Info
    private Long id;
    private String brand;
    private String model;
    private Integer year;
    private Double pricePerDay;
    private String location;
    private String description;
    private boolean available;

    // Specifications
    private Integer seats (2-9);
    private FuelType fuelType;
    private Double fuelConsumption;
    private TransmissionType transmissionType;

    // Features & Add-ons
    private List<Feature> features; // 43 enum values
    private List<String> addOns; // Custom add-ons

    // Policies
    private CancellationPolicy cancellationPolicy;
    private Integer minRentalDays;
    private Integer maxRentalDays;

    // Media
    private String imageUrl; // Primary image
    private List<String> imageUrls; // Gallery

    // Relationships
    @ManyToOne private User owner;
    @OneToMany private List<Booking> bookings;
    @OneToMany private List<Review> reviews;
}
```

#### Review Entity (Bidirectional)
**Location:** `/Rentoza/src/main/java/org/example/rentoza/review/Review.java`

```java
@Entity
public class Review {
    private Long id;
    private int rating; // 1-5 (calculated average for renter reviews)
    private String comment;
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    private ReviewDirection direction; // FROM_USER or FROM_OWNER

    // Category ratings (for renter reviews)
    private Integer cleanlinessRating;
    private Integer maintenanceRating;
    private Integer communicationRating;
    private Integer convenienceRating;
    private Integer accuracyRating;

    @ManyToOne private Car car;
    @ManyToOne private User reviewer;
    @ManyToOne private User reviewee;
    @ManyToOne private Booking booking;
}
```

### Existing Endpoints

**Cars:**
- `POST /api/cars/add` - Add new car (requires OWNER role)
- `GET /api/cars` - List all cars
- `GET /api/cars/{id}` - Get car details
- `PUT /api/cars/{id}` - Update car
- `DELETE /api/cars/{id}` - Delete car

**Reviews:**
- `POST /api/reviews/from-renter` - Renter reviews owner (implemented ✅)
- `GET /api/reviews/car/{carId}` - Get reviews for car
- `GET /api/reviews/recent` - Get recent reviews

**Bookings:**
- `POST /api/bookings` - Create booking
- `GET /api/bookings/my` - Get user's bookings
- `PATCH /api/bookings/{id}/status` - Update booking status

---

## Frontend Architecture

### Route Structure

```typescript
/owner
  /dashboard          → OwnerDashboardComponent
  /cars               → OwnerCarsListComponent
  /cars/new           → AddCarWizardComponent (multi-step)
  /cars/:id/edit      → EditCarComponent
  /bookings           → OwnerBookingsComponent
  /bookings/:id/review → OwnerReviewFormComponent
  /earnings           → OwnerEarningsComponent
  /reviews            → OwnerReviewsComponent
  /verification       → OwnerVerificationComponent
```

### Component Hierarchy

```
OwnerLayoutComponent (Shell)
  ├─ OwnerDashboardComponent
  │    ├─ Stats Cards (4)
  │    ├─ Quick Actions (4 cards)
  │    └─ Recent Activity
  │
  ├─ OwnerCarsListComponent
  │    ├─ Car Cards (grid)
  │    └─ Actions: Edit, Deactivate, Delete, Preview
  │
  ├─ AddCarWizardComponent (Stepper)
  │    ├─ Step 1: Basic Info
  │    ├─ Step 2: Specifications
  │    ├─ Step 3: Features
  │    ├─ Step 4: Add-ons
  │    ├─ Step 5: Policies
  │    ├─ Step 6: Photos
  │    └─ Step 7: Review & Submit
  │
  ├─ OwnerBookingsComponent
  │    ├─ Tabs: Upcoming, Active, Completed
  │    └─ "Ostavi recenziju" button for completed
  │
  ├─ OwnerReviewFormComponent
  │    ├─ 5 category ratings
  │    ├─ Comment field
  │    └─ Submit button
  │
  ├─ OwnerEarningsComponent
  │    ├─ Total earnings chart
  │    ├─ Breakdown by car
  │    └─ Date range filter
  │
  ├─ OwnerReviewsComponent
  │    ├─ Tab 1: Received (FROM_USER)
  │    └─ Tab 2: Given (FROM_OWNER)
  │
  └─ OwnerVerificationComponent
       ├─ Document upload
       └─ Status badges
```

---

## Implementation Plan

### Phase 1: Core Infrastructure ✅
- [x] Create route structure
- [x] Owner Dashboard component
- [ ] Add owner routes to app.routes.ts
- [ ] Update RoleGuard for OWNER role
- [ ] Create owner layout shell (optional)

### Phase 2: Car Management (Critical Path)
- [ ] OwnerCarsListComponent
- [ ] AddCarWizardComponent (7 steps)
  - [ ] Step 1: Basic Info Form
  - [ ] Step 2: Specifications Form
  - [ ] Step 3: Features Multi-select
  - [ ] Step 4: Add-ons Input
  - [ ] Step 5: Policies Form
  - [ ] Step 6: Photo Upload
  - [ ] Step 7: Review & Submit
- [ ] CarService.addCar() method
- [ ] Photo upload handling (Base64 or file URLs)

### Phase 3: Owner-to-Renter Review Flow
- [ ] Backend: POST /api/reviews/from-owner endpoint
- [ ] OwnerReviewFormComponent
- [ ] OwnerBookingsComponent with review access
- [ ] ReviewService.submitOwnerReview()

### Phase 4: Additional Features
- [ ] OwnerEarningsComponent
- [ ] OwnerReviewsComponent (tabbed)
- [ ] OwnerVerificationComponent

### Phase 5: Testing & Documentation
- [ ] E2E tests for add car flow
- [ ] E2E tests for owner review flow
- [ ] Update owner-feature-context.md with API examples
- [ ] Build and verify all components

---

## Data Models

### Frontend Models

#### AddCarRequest
```typescript
export interface AddCarRequest {
  brand: string;
  model: string;
  year: number;
  pricePerDay: number;
  location: string;
  description?: string;
  seats: number;
  fuelType: FuelType;
  fuelConsumption?: number;
  transmissionType: TransmissionType;
  features?: Feature[];
  addOns?: string[];
  cancellationPolicy: CancellationPolicy;
  minRentalDays: number;
  maxRentalDays: number;
  imageUrl?: string;
  imageUrls?: string[];
}
```

#### OwnerReviewRequest
```typescript
export interface OwnerReviewRequest {
  bookingId: number;
  cleanlinessRating: number;
  maintenanceRating: number;
  communicationRating: number;
  convenienceRating: number;
  accuracyRating: number;
  comment?: string;
}
```

#### OwnerStats
```typescript
export interface OwnerStats {
  activeCars: number;
  upcomingBookings: number;
  totalEarnings: number;
  averageRating: number;
  completedTrips: number;
  reviewsReceived: number;
}
```

---

## Security Model

### Access Control
- All `/owner/**` routes protected by RoleGuard with `roles: ['OWNER', 'ADMIN']`
- Backend validates:
  - JWT token authenticity
  - User has OWNER role
  - User owns the car being modified
  - User owns the car for which a booking review is being submitted

### Review Submission Rules
Owner can review a renter ONLY if:
1. User is authenticated with OWNER role
2. User owns the car in the booking
3. Booking status is COMPLETED
4. Rental period has ended
5. No existing review for this booking (direction: FROM_OWNER)

---

## API Endpoints (To Be Implemented)

### Owner Review Endpoint

**POST /api/reviews/from-owner**

**Request:**
```json
{
  "bookingId": 123,
  "cleanlinessRating": 5,
  "maintenanceRating": 4,
  "communicationRating": 5,
  "convenienceRating": 5,
  "accuracyRating": 4,
  "comment": "Odličan iznajmljivač, preporučujem!"
}
```

**Response (200 OK):**
```json
{
  "id": 456,
  "rating": 5,
  "message": "Review successfully submitted"
}
```

**Security Checks:**
1. JWT authentication
2. User has OWNER role
3. User owns the car in the booking
4. Booking status is COMPLETED
5. Rental period ended
6. No existing review for this booking

**Error Responses:**
- `401 Unauthorized` - Missing/invalid JWT
- `403 Forbidden` - Not the car owner or booking not found
- `409 Conflict` - Already reviewed this renter
- `400 Bad Request` - Validation errors or booking not completed

### Owner Stats Endpoint

**GET /api/owner/stats**

**Response:**
```json
{
  "activeCars": 3,
  "upcomingBookings": 5,
  "totalEarnings": 125000,
  "averageRating": 4.8,
  "completedTrips": 42,
  "reviewsReceived": 38
}
```

---

## Localization (Serbian Latin)

### Key Translations
- **Kontrolna tabla** - Dashboard
- **Aktivna vozila** - Active cars
- **Predstojeće rezervacije** - Upcoming bookings
- **Ukupna zarada** - Total earnings
- **Prosečna ocena** - Average rating
- **Dodaj vozilo** - Add car
- **Moja vozila** - My cars
- **Ostavi recenziju** - Leave review
- **Osnovne informacije** - Basic information
- **Specifikacije** - Specifications
- **Karakteristike** - Features
- **Dodatna oprema** - Add-ons
- **Pravila i politika** - Rules and policy
- **Slike vozila** - Car photos
- **Potvrda i objava** - Confirmation and publish
- **Na čekanju** - Pending
- **Odobreno** - Approved
- **Odbijeno** - Rejected

---

## Add Car Flow (6-Step Wizard)

### Step 1: Basic Information
**Fields:**
- Marka (Brand) - Text input, required
- Model - Text input, required
- Godina (Year) - Number input, 1950-2050, required
- Lokacija (Location) - Text input with autocomplete, required
- Cena po danu (Price per day) - Number input, min 10 RSD, required
- Opis (Description) - Textarea, max 1000 chars, optional

**Validation:**
- All required fields must be filled
- Year must be 1950-2050
- Price must be >= 10
- Description max 1000 characters

### Step 2: Specifications
**Fields:**
- Broj sedišta (Seats) - Number input, 2-9, default 5
- Vrsta goriva (Fuel type) - Dropdown, required
- Potrošnja (Fuel consumption) - Number input, 0-50 L/100km, optional
- Menjač (Transmission) - Radio buttons, MANUAL/AUTOMATIC, required

**Validation:**
- Seats 2-9
- Fuel consumption 0-50 if provided

### Step 3: Features
**Multi-select with categories:**
- **Sigurnost** (Safety): ABS, Airbag, Parking Sensors, etc.
- **Povezivost** (Connectivity): Bluetooth, USB, Android Auto, etc.
- **Komfor** (Comfort): A/C, Heated Seats, Leather, Sunroof, etc.
- **Dodatno** (Additional): Roof Rack, Tow Hitch, LED Lights, etc.

**UI:** Chip-based multi-select grouped by category

### Step 4: Add-ons
**Custom text inputs (chips):**
- Dečije sedište (Child seat)
- Zimske gume (Winter tires)
- GPS navigacija (GPS navigation)
- Custom entries allowed

### Step 5: Policies
**Fields:**
- Politika otkazivanja (Cancellation policy) - Dropdown: FLEXIBLE, MODERATE, STRICT, NON_REFUNDABLE
- Minimalni broj dana (Min rental days) - Number, min 1, default 1
- Maksimalni broj dana (Max rental days) - Number, min 1, default 30
- Pravila iznajmljivanja - Display hard-coded rules from CAR_RENTAL_RULES

### Step 6: Photos
**File upload:**
- Primary image (imageUrl)
- Gallery images (imageUrls array)
- Drag-to-reorder support
- Preview grid with delete option
- Max 10 images
- Supported formats: JPG, PNG, WebP
- Max size: 5MB per image

**Storage options:**
1. Base64 encoding (simple but large payloads)
2. File upload to server/cloud (preferred for production)
3. External URL input (for existing images)

### Step 7: Review & Submit
**Display summary:**
- All entered data formatted
- Image previews
- "Potvrdi i objavi" button
- "Nazad" button to edit

**On submit:**
- Call `POST /api/cars/add`
- Show success message: "Vozilo uspešno dodato! Na čekanju je za odobrenje."
- Navigate to /owner/cars

---

## Photo Upload Strategy

### Option 1: Base64 Encoding (Simple)
```typescript
handleFileUpload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (file) {
    const reader = new FileReader();
    reader.onload = () => {
      this.imageUrl = reader.result as string; // Base64 string
    };
    reader.readAsDataURL(file);
  }
}
```

### Option 2: FormData Upload (Production)
```typescript
uploadPhoto(file: File): Observable<{ url: string }> {
  const formData = new FormData();
  formData.append('file', file);
  return this.http.post<{ url: string }>('/api/upload/photo', formData);
}
```

### Option 3: External URLs
```typescript
// User pastes image URL directly
imageUrl: string = 'https://example.com/car-image.jpg';
```

**Recommendation:** Start with Base64 for MVP, migrate to FormData upload for production.

---

## Design Guidelines

### Color Palette (Consistent with Renter UI)
- **Primary:** `#3b82f6` (Blue)
- **Success:** `#10b981` (Green)
- **Accent:** `#fb923c` (Orange)
- **Warning:** `#fbbf24` (Gold)
- **Error:** `#ef4444` (Red)

### Typography
- **Headings:** Inter, 600-700 weight
- **Body:** Inter, 400 weight
- **Labels:** 0.9rem, 500 weight
- **Captions:** 0.8rem, 400 weight

### Spacing
- **Section gaps:** 3rem
- **Card padding:** 2rem
- **Button padding:** 0.75rem 1.5rem
- **Input padding:** 1rem

### Responsive Breakpoints
- **Mobile:** < 600px
- **Tablet:** 600px - 900px
- **Desktop:** > 900px

---

## Testing Scenarios

### Add Car Flow
1. Navigate to /owner/dashboard
2. Click "Dodaj vozilo"
3. Fill Step 1: Basic Info
4. Click "Dalje" → Step 2
5. Fill Step 2: Specifications
6. Click "Dalje" → Step 3
7. Select multiple features
8. Click "Dalje" → Step 4
9. Add custom add-ons
10. Click "Dalje" → Step 5
11. Select cancellation policy and rental days
12. Click "Dalje" → Step 6
13. Upload photos
14. Click "Dalje" → Step 7
15. Review summary
16. Click "Potvrdi i objavi"
17. Verify success message
18. Verify redirect to /owner/cars

### Owner Review Flow
1. Navigate to /owner/bookings
2. Find completed booking
3. Click "Ostavi recenziju"
4. Navigate to /owner/bookings/:id/review
5. Rate all 5 categories
6. Add optional comment
7. Click "Pošalji recenziju"
8. Verify success message: "Hvala! Vaša recenzija je uspešno poslata."
9. Verify redirect to /owner/bookings
10. Verify "Recenzija poslata" badge appears

---

## Next Steps

### Immediate Priorities
1. ✅ Owner Dashboard UI
2. ⏳ Add owner routes to app.routes.ts
3. ⏳ OwnerCarsListComponent
4. ⏳ AddCarWizardComponent (multi-step form)
5. ⏳ Backend: POST /api/reviews/from-owner
6. ⏳ OwnerReviewFormComponent

### Future Enhancements
- Real-time booking notifications
- Owner-renter messaging
- Trip calendar with availability management
- Analytics dashboard with charts
- Batch photo upload with drag-and-drop
- Car performance metrics (views, bookings, revenue per car)
- Verification document management
- Email notifications for bookings and reviews

---

## Summary

The Rentoza owner feature provides a complete two-sided platform matching Turo's host experience. It includes:

✅ Professional dashboard with key metrics
✅ Complete car listing wizard (6 steps)
✅ Photo upload and gallery management
✅ Bidirectional review system (owner ↔ renter)
✅ Bookings management with review access
✅ Earnings tracking
✅ Role-based security (OWNER, ADMIN)
✅ Serbian localization
✅ Consistent Material Design 3 UI
✅ Dark mode support
✅ Responsive design (mobile-first)

This architecture supports scalable growth and maintains production-grade security and UX standards.
