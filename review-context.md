# Rentoza Review System - Technical Documentation

## Overview

The Rentoza review system implements a secure, bidirectional review flow between renters and car owners, inspired by trust-first platforms like Turo and Airbnb. This document outlines the complete architecture, data models, API endpoints, and integration patterns.

---

## System Architecture

### Review Directions

The system supports two review directions:

1. **FROM_USER** (Renter → Owner)
   - Renters review their rental experience
   - Reviews the owner's service, car quality, and accuracy
   - Includes category-based ratings (cleanliness, maintenance, communication, convenience, accuracy)
   - Average of all category ratings becomes the overall rating

2. **FROM_OWNER** (Owner → Renter)
   - Owners review renter behavior
   - Reviews renter's care for the vehicle, communication, and adherence to rules
   - Single overall rating with optional comment
   - Future: Can be extended with categories

---

## Data Model

### Review Entity

**Location:** `/Rentoza/src/main/java/org/example/rentoza/review/Review.java`

```java
@Entity
@Table(name = "reviews")
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Overall rating (1-5) - calculated average for renter reviews
    @Min(1) @Max(5)
    private int rating;

    // Optional comment (max 500 characters)
    @Size(max = 500)
    private String comment;

    // Creation timestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Review direction (FROM_USER or FROM_OWNER)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewDirection direction;

    // Category-based ratings (1-5) for renter reviews
    @Min(1) @Max(5)
    private Integer cleanlinessRating;

    @Min(1) @Max(5)
    private Integer maintenanceRating;

    @Min(1) @Max(5)
    private Integer communicationRating;

    @Min(1) @Max(5)
    private Integer convenienceRating;

    @Min(1) @Max(5)
    private Integer accuracyRating;

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id")
    private Car car;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewee_id")
    private User reviewee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;
}
```

### Key Relationships

- **Car** (1:N) → One car can have many reviews
- **Reviewer** (1:N) → One user can write many reviews
- **Reviewee** (1:N) → One user can receive many reviews
- **Booking** (1:2) → One booking can have max 2 reviews (one from renter, one from owner)

---

## API Endpoints

### 1. Submit Renter Review

**Endpoint:** `POST /api/reviews/from-renter`

**Authentication:** JWT Required (Bearer token)

**Request Body:**
```json
{
  "bookingId": 123,
  "cleanlinessRating": 5,
  "maintenanceRating": 4,
  "communicationRating": 5,
  "convenienceRating": 5,
  "accuracyRating": 4,
  "comment": "Great car and excellent host!"
}
```

**Validation:**
- All category ratings required (1-5)
- Comment optional (max 500 chars)
- Booking ID required

**Security Checks:**
1. User is authenticated (via JWT)
2. User is the renter of the booking
3. Booking status is COMPLETED
4. Rental period has ended
5. No existing review for this booking (prevents duplicates)

**Response (200 OK):**
```json
{
  "id": 456,
  "rating": 5,
  "message": "Review successfully submitted"
}
```

**Error Responses:**
- `401 Unauthorized` - Missing/invalid JWT
- `403 Forbidden` - Not the booking's renter or booking not found
- `409 Conflict` - Already reviewed this booking
- `400 Bad Request` - Validation errors or booking not completed

**Implementation:** `/Rentoza/src/main/java/org/example/rentoza/review/ReviewController.java:68-102`

---

### 2. Get Reviews for Car

**Endpoint:** `GET /api/reviews/car/{carId}`

**Authentication:** Not required

**Response:**
```json
[
  {
    "id": "456",
    "rating": 5,
    "comment": "Great car!",
    "createdAt": "2025-01-15T10:00:00Z",
    "direction": "FROM_USER",
    "reviewerFirstName": "Marko",
    "reviewerLastName": "Petrović",
    "carBrand": "Toyota",
    "carModel": "Corolla"
  }
]
```

---

### 3. Get Recent Reviews

**Endpoint:** `GET /api/reviews/recent`

**Returns:** Latest 10 reviews across all cars

---

### 4. Get Average Rating

**Endpoint:** `GET /api/reviews/car/{carId}/average`

**Response:**
```json
{
  "averageRating": 4.7
}
```

---

## Frontend Integration

### Review Model

**Location:** `/rentoza-frontend/src/app/core/models/review.model.ts`

```typescript
export interface RenterReviewRequest {
  bookingId: number;
  cleanlinessRating: number;
  maintenanceRating: number;
  communicationRating: number;
  convenienceRating: number;
  accuracyRating: number;
  comment?: string;
}

export const REVIEW_CATEGORIES = [
  { key: 'cleanlinessRating', label: 'Čistoća', icon: 'cleaning_services' },
  { key: 'maintenanceRating', label: 'Održavanje', icon: 'build' },
  { key: 'communicationRating', label: 'Komunikacija', icon: 'chat' },
  { key: 'convenienceRating', label: 'Pogodnost', icon: 'star' },
  { key: 'accuracyRating', label: 'Tačnost opisa', icon: 'verified' },
];
```

### Review Service

**Location:** `/rentoza-frontend/src/app/core/services/review.service.ts`

```typescript
submitRenterReview(request: RenterReviewRequest): Observable<{ id: number; rating: number; message: string }> {
  return this.http.post<{ id: number; rating: number; message: string }>(
    `${this.baseUrl}/reviews/from-renter`,
    request,
    { withCredentials: true }
  );
}
```

---

## Booking Integration

### hasReview Flag

The booking response includes a `hasReview` flag that indicates whether the user has already reviewed the booking:

**Backend:** `/Rentoza/src/main/java/org/example/rentoza/booking/dto/UserBookingResponseDTO.java`
```java
private Boolean hasReview;
private Integer reviewRating;
private String reviewComment;
```

**Frontend:** `/rentoza-frontend/src/app/core/models/booking.model.ts`
```typescript
export interface UserBooking {
  // ... other fields
  hasReview: boolean;
  reviewRating: number | null;
  reviewComment: string | null;
}
```

### Booking Card UI Logic

In the booking history component (`booking-history.component.html`):

```html
<!-- For COMPLETED bookings only -->
<div *ngIf="category === 'past'" class="booking-card__actions">
  <!-- If not reviewed: show "Dodaj recenziju" button -->
  <button
    *ngIf="!booking.hasReview"
    mat-raised-button
    color="accent"
    [routerLink]="['/bookings', booking.id, 'review']"
  >
    <mat-icon>rate_review</mat-icon>
    Dodaj recenziju
  </button>

  <!-- If reviewed: show review badge -->
  <div *ngIf="booking.hasReview" class="review-badge">
    <mat-icon>check_circle</mat-icon>
    <span>Vaša ocena: ★{{ booking.reviewRating }}</span>
  </div>
</div>
```

---

## Review Form Component (Frontend)

### Route Configuration

**Path:** `/bookings/:id/review`

**Guard:** `authGuard` (user must be authenticated)

**Component:** `AddReviewComponent` (to be created in `/features/bookings/pages/add-review/`)

### Component Structure

```typescript
@Component({
  selector: 'app-add-review',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
  ],
  templateUrl: './add-review.component.html',
  styleUrls: ['./add-review.component.scss']
})
export class AddReviewComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly reviewService = inject(ReviewService);
  private readonly bookingService = inject(BookingService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);

  protected readonly bookingId = signal<number>(0);
  protected readonly booking = signal<UserBooking | null>(null);
  protected readonly isLoading = signal(true);
  protected readonly isSubmitting = signal(false);

  protected readonly categories = REVIEW_CATEGORIES.map(cat => ({
    ...cat,
    rating: signal(0)
  }));

  protected readonly reviewForm = this.fb.nonNullable.group({
    comment: ['', [Validators.maxLength(500)]]
  });

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.bookingId.set(+id);
      this.loadBooking();
    }
  }

  protected setRating(category: typeof this.categories[0], rating: number) {
    category.rating.set(rating);
  }

  protected submitReview() {
    if (!this.canSubmit()) return;

    this.isSubmitting.set(true);
    const request: RenterReviewRequest = {
      bookingId: this.bookingId(),
      cleanlinessRating: this.categories[0].rating(),
      maintenanceRating: this.categories[1].rating(),
      communicationRating: this.categories[2].rating(),
      convenienceRating: this.categories[3].rating(),
      accuracyRating: this.categories[4].rating(),
      comment: this.reviewForm.value.comment || undefined
    };

    this.reviewService.submitRenterReview(request).subscribe({
      next: () => {
        this.snackBar.open('Hvala! Vaša recenzija je uspešno poslata.', 'Zatvori', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.router.navigate(['/bookings']);
      },
      error: (err) => {
        const message = err.error?.error || 'Greška prilikom slanja recenzije.';
        this.snackBar.open(message, 'Zatvori', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
        this.isSubmitting.set(false);
      }
    });
  }

  protected canSubmit(): boolean {
    return this.categories.every(cat => cat.rating() > 0) && !this.isSubmitting();
  }
}
```

### UI Template

The template should include:
1. Car summary card (brand, model, image, owner name)
2. Star rating sections for each category
3. Optional comment textarea
4. Submit button (disabled until all categories rated)
5. Loading/submitting states

---

## Security Model

### Access Control Layers

#### 1. Frontend Guards
- `authGuard` - Ensures user is logged in
- Route param validation - Ensures booking ID is valid

#### 2. Backend Security
- **JWT Authentication** - Validates user identity
- **Ownership Validation** - Verifies user is the booking's renter
- **Status Validation** - Ensures booking is COMPLETED
- **Date Validation** - Ensures rental period has ended
- **Duplicate Prevention** - Checks for existing review

### Security Flow

```
User clicks "Dodaj recenziju" button
  ↓
Navigate to /bookings/:id/review (authGuard)
  ↓
Component loads booking data (validates ownership client-side)
  ↓
User fills form and submits
  ↓
POST /api/reviews/from-renter (JWT in header)
  ↓
Backend validates:
  - JWT authenticity
  - User is renter
  - Booking is COMPLETED
  - Rental ended
  - No existing review
  ↓
Review saved
  ↓
Success response → Redirect to /bookings
  ↓
Booking card now shows review badge
```

---

## Extension Points

### Future Enhancements

#### 1. Owner → Renter Reviews
- Extend `createOwnerToRenterReview()` method
- Create separate form component
- Add to owner dashboard

#### 2. Review Responses
- Allow reviewees to respond to reviews
- Add `responses` table with FK to `reviews`

#### 3. Image Uploads
- Support photo uploads with reviews
- Store URLs in separate `review_images` table

#### 4. Review Moderation
- Add admin review approval workflow
- Flagging system for inappropriate content

#### 5. Verified Reviews
- Add "Verified Stay" badge
- Link to completed booking evidence

---

## Testing Guidelines

### Backend Unit Tests

```java
@Test
void shouldPreventDuplicateReviews() {
    // Submit first review
    Review first = service.createRenterReview(validRequest, renterEmail);
    assertNotNull(first.getId());

    // Attempt duplicate
    assertThrows(RuntimeException.class, () -> {
        service.createRenterReview(validRequest, renterEmail);
    });
}

@Test
void shouldRejectUnauthorizedUser() {
    assertThrows(RuntimeException.class, () -> {
        service.createRenterReview(request, wrongUserEmail);
    });
}
```

### Frontend E2E Tests

```typescript
it('should submit review successfully', () => {
  // Navigate to completed booking
  cy.visit('/bookings');
  cy.get('[data-test="completed-booking"]').first().click();

  // Click "Dodaj recenziju"
  cy.get('[data-test="add-review-button"]').click();

  // Rate all categories (5 stars each)
  cy.get('[data-test="category-stars"]').each($el => {
    cy.wrap($el).find('[data-test="star-5"]').click();
  });

  // Add comment
  cy.get('[data-test="comment-field"]').type('Great experience!');

  // Submit
  cy.get('[data-test="submit-review"]').click();

  // Verify success
  cy.contains('Hvala! Vaša recenzija je uspešno poslata.');
  cy.url().should('include', '/bookings');
  cy.get('[data-test="review-badge"]').should('be.visible');
});
```

---

## Database Schema

### reviews Table

```sql
CREATE TABLE reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rating INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    direction VARCHAR(20) NOT NULL,

    -- Category ratings (nullable for owner reviews)
    cleanliness_rating INT CHECK (cleanliness_rating BETWEEN 1 AND 5),
    maintenance_rating INT CHECK (maintenance_rating BETWEEN 1 AND 5),
    communication_rating INT CHECK (communication_rating BETWEEN 1 AND 5),
    convenience_rating INT CHECK (convenience_rating BETWEEN 1 AND 5),
    accuracy_rating INT CHECK (accuracy_rating BETWEEN 1 AND 5),

    -- Foreign keys
    car_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    reviewee_id BIGINT NOT NULL,
    booking_id BIGINT,

    FOREIGN KEY (car_id) REFERENCES cars(id),
    FOREIGN KEY (reviewer_id) REFERENCES users(id),
    FOREIGN KEY (reviewee_id) REFERENCES users(id),
    FOREIGN KEY (booking_id) REFERENCES bookings(id),

    -- Indexes for performance
    INDEX idx_car_id (car_id),
    INDEX idx_reviewer_id (reviewer_id),
    INDEX idx_reviewee_id (reviewee_id),
    INDEX idx_booking_id (booking_id),
    INDEX idx_direction (direction),

    -- Unique constraint: one review per booking per direction
    UNIQUE KEY unique_booking_direction (booking_id, direction)
);
```

---

## Localization

All UI text is in Serbian (Latin):

- **Čistoća** - Cleanliness
- **Održavanje** - Maintenance
- **Komunikacija** - Communication
- **Pogodnost** - Convenience
- **Tačnost opisa** - Accuracy of description
- **Dodaj recenziju** - Add review
- **Pošalji recenziju** - Submit review
- **Komentar (opciono)** - Comment (optional)
- **Hvala! Vaša recenzija je uspešno poslata.** - Thank you! Your review was successfully submitted.

---

## Summary

The Rentoza review system provides a secure, category-based review flow that:

✅ Enforces strict access control at multiple layers
✅ Prevents duplicate reviews
✅ Integrates seamlessly with the booking flow
✅ Provides detailed category ratings for better feedback
✅ Follows modern car-sharing platform UX patterns
✅ Is fully localized for the Serbian market
✅ Is extensible for future enhancements

This architecture supports the trust-first model essential for peer-to-peer car sharing platforms while maintaining production-grade security and user experience standards.
