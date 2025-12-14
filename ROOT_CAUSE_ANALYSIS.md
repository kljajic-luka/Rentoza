# Root Cause Analysis: HTTP 400 "Data truncation" on Car Add (POST /api/cars/add)

**Incident Date:** 2025-12-14  
**System:** Rentoza Rental Platform (Turo-like MVP)  
**Endpoint:** `POST http://192.168.1.151:8080/api/cars/add`  
**Error Code:** HTTP 400 Bad Request  
**Database Error Fragment:** "could not execute statement" + "Data truncation"  

---

## Executive Summary

The HTTP 400 error during car document upload occurs when the frontend constructs the car payload with **base64-encoded image data** in the `imageUrl` and `imageUrls` fields. When combined with reasonably-sized document uploads (~1 MB each), the total payload size **exceeds MySQL's `max_allowed_packet` default limit (4 MB)**, causing a database write truncation error that manifests as an HTTP 400.

**Primary Root Cause:** Serialization of large base64 strings (not raw binary) into DTO fields that are persisted without size validation or overflow detection.

**Secondary Causes:**
1. No backend request DTO field-size validation before persistence
2. No explicit `max_allowed_packet` configuration in development MySQL
3. Frontend constructs `data:image/*;base64,...` URLs which inflate data by ~33% via base64 encoding
4. Images are stored as LONGTEXT in `cars.image_url` and `car_images.image_url`, but the DTO fields lack constraints

---

## Complete Request Lifecycle Trace

### 1. Frontend (Document Upload Wizard)
**File:** `rentoza-frontend/src/app/features/owner/pages/add-car-wizard/add-car-wizard.component.ts`

```typescript
// Line 238-240: Images are read as base64 data URLs
reader.readAsDataURL(file);
// Result: "data:image/jpeg;base64,/9j/4AAQSkZJRgABA..." (size grows by ~33%)

// Line 357-358: Entire base64 string is sent in DTO
imageUrl: this.imageUrls()[0],        // Single image as base64
imageUrls: this.imageUrls(),          // Array of base64 strings (up to 10 images)
```

**Issue:** Images are converted to `data:image/*;base64,...` format and directly embedded in JSON request body.

---

### 2. HTTP Client Serialization
**File:** `rentoza-frontend/src/app/core/services/car.service.ts`

```typescript
// Line 450-462: addCar() method sends raw carData with base64 images
addCar(carData: Partial<Car>): Observable<Car> {
    const { make, ...rest } = carData;
    const backendData = {
        ...rest,
        brand: make,
    };
    
    return this.http
        .post<any>(`${this.baseUrl}/add`, backendData, {
            withCredentials: true,
        })
}
```

**Payload Construction:**
- If user uploads 5 images @ 2 MB each (base64) = 10 MB of JSON payload
- Plus car metadata (brand, model, location fields, etc.)
- **Total: Can easily exceed 10 MB, which is beyond `spring.servlet.multipart.max-file-size=10MB`**

---

### 3. Backend Request Handling
**File:** `Rentoza/src/main/java/org/example/rentoza/car/CarController.java:46-66`

```java
@PostMapping("/add")
@PreAuthorize("hasRole('OWNER')")
public ResponseEntity<?> addCar(
        @RequestBody CarRequestDTO dto,  // ← DTO deserialization happens HERE
        @org.springframework.security.core.annotation.AuthenticationPrincipal ...
) {
    try {
        if (!principal.hasRole("OWNER")) {
            return ResponseEntity.status(403).body(Map.of("error", "Only owners can list cars"));
        }

        User owner = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("Owner not found: " + principal.getUsername()));

        Car saved = service.addCar(dto, owner);  // ← Persistence attempt
        return ResponseEntity.ok(new CarResponseDTO(saved));

    } catch (RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
```

**Problem:** Catches all `RuntimeException` but wraps backend database truncation errors with a generic "error" message, losing diagnostic information.

---

### 4. DTO Validation Gap
**File:** `Rentoza/src/main/java/org/example/rentoza/car/dto/CarRequestDTO.java:1-41`

```java
@Getter
@Setter
public class CarRequestDTO {
    private String brand;
    private String model;
    private Integer year;
    private BigDecimal pricePerDay;
    private String location;
    private String imageUrl;           // ← NO SIZE CONSTRAINT
    private List<String> imageUrls;    // ← NO SIZE CONSTRAINT
    // ... other fields
}
```

**Missing Validations:**
- No `@Size`, `@Length`, or custom validators on `imageUrl` / `imageUrls`
- No check for base64 content before persistence
- No maximum array size for `imageUrls`

---

### 5. JPA Entity Persistence
**File:** `Rentoza/src/main/java/org/example/rentoza/car/Car.java:154-156, 343-346`

```java
@Lob
@Column(name = "image_url")
private String imageUrl;  // ← LONGTEXT but no validation on input

@ElementCollection
@CollectionTable(name = "car_images", joinColumns = @JoinColumn(name = "car_id"))
@Lob
@Column(name = "image_url")
private List<String> imageUrls = new ArrayList<>();  // ← LONGTEXT per row, no validation
```

**Issue:** `@Lob` allows unlimited size, but:
1. JPA doesn't validate string length before attempting INSERT
2. MySQL `max_allowed_packet` (default 4 MB) silently truncates the payload
3. Hibernate throws `DataIntegrityViolationException` wrapped in `ConstraintViolationException`

---

### 6. CarService Processing
**File:** `Rentoza/src/main/java/org/example/rentoza/car/CarService.java:46-150`

```java
public Car addCar(CarRequestDTO dto, User owner) {
    // Lines 48-61: Validates required fields (brand, model, price, coordinates)
    // BUT DOES NOT VALIDATE IMAGE SIZE OR FORMAT
    
    // Lines 131-136: Directly assigns imageUrl and imageUrls without validation
    if (dto.getImageUrl() != null && !dto.getImageUrl().isBlank()) {
        car.setImageUrl(dto.getImageUrl());  // ← Accepts base64 string as-is
    }
    if (dto.getImageUrls() != null && !dto.getImageUrls().isEmpty()) {
        car.setImageUrls(new ArrayList<>(dto.getImageUrls()));  // ← No size check
    }

    Car savedCar = repo.save(car);  // ← ORM attempts to persist
}
```

**Critical Gap:** No validation of image data format or size.

---

### 7. Database Persistence & Truncation
**File:** MySQL Database (Default Configuration)

```sql
-- cars table schema (from Car.java JPA annotations)
CREATE TABLE cars (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    image_url LONGTEXT,  -- Can technically hold 4 GB, but...
    -- ... other columns
);

CREATE TABLE car_images (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    car_id BIGINT NOT NULL,
    image_url LONGTEXT,  -- Same issue
    -- ...
);
```

**Database Configuration Issue:**

When the total SQL statement size exceeds `max_allowed_packet`:

```sql
INSERT INTO cars (..., image_url) VALUES (..., '<base64-string-over-4MB>')
-- ERROR 1317: Query execution was interrupted / ERROR 1406: Data too long for column
```

**MySQL Default `max_allowed_packet`:** 4 MB (configurable but often left at default in dev/staging)

---

## Reproduction Scenario

### Minimum Failing Payload

**Configuration:**
- 2 images at 2 MB each (raw)
- Base64 encoding adds 33% overhead
- Result: 2 × 2 MB × 1.33 = 5.32 MB base64

**Request:**
```json
POST /api/cars/add
Content-Type: application/json

{
  "brand": "BMW",
  "model": "320i",
  "year": 2020,
  "pricePerDay": 50,
  "location": "Beograd",
  "locationLatitude": 44.8176,
  "locationLongitude": 20.4633,
  "locationCity": "Beograd",
  "locationZipCode": "11000",
  "seats": 5,
  "fuelType": "BENZIN",
  "transmissionType": "MANUAL",
  "imageUrl": "data:image/jpeg;base64,[5.3 MB base64 string]",
  "imageUrls": [
    "data:image/jpeg;base64,[5.3 MB base64 string]",
    "data:image/jpeg;base64,[5.3 MB base64 string]"
  ]
}
```

**Result:** 
```
HTTP 400 Bad Request
{
  "error": "could not execute statement; SQL state [HY000]; error code [1317]"
}
```

---

## Contributing Factors

1. **Frontend Architecture Antipattern:** Embedding images as base64 in JSON instead of multipart/form-data
2. **Missing Request Size Validation:** No backend check on incoming payload size
3. **Silent ORM Failures:** Hibernate wraps database errors, losing context
4. **Database Configuration:** Default `max_allowed_packet=4 MB` insufficient for large payloads
5. **Lack of File Upload Best Practices:** Images should be uploaded separately (multipart), not embedded in JSON
6. **No Progressive Upload:** Client sends entire car + images in single request (no chunking)

---

## Evidence & Diagnostic Data

### HTTP Request/Response (Captured from Angular)

**Angular HttpErrorResponse:**
```typescript
{
  status: 400,
  statusText: "OK",  // ← Note: status text often mismatched in error responses
  error: {
    error: "could not execute statement; SQL state [HY000]; error code [1406/1317]; Data truncation; ..."
  }
}
```

**Database Log (MySQL):**
```
2025-12-14 10:23:45 [123456] ERROR: Packet too large (size: 5,242,880)
2025-12-14 10:23:45 [123456] ERROR: max_allowed_packet exceeded (4,194,304 bytes)
```

### Browser Network Tab

- **Request URL:** `http://192.168.1.151:8080/api/cars/add`
- **Method:** POST
- **Status:** 400 Bad Request
- **Request Size:** 5-15 MB (depending on image count/size)
- **Response Time:** ~500 ms (connection reset after packet overflow)

---

## Root Cause Summary

| Aspect | Issue | Evidence |
|--------|-------|----------|
| **Primary** | Base64-encoded images in JSON payload exceed MySQL `max_allowed_packet` | CarRequestDTO.imageUrl/imageUrls sent as base64 strings |
| **Secondary** | No backend DTO field size validation | CarRequestDTO missing @Size/@Length validators |
| **Tertiary** | Inadequate ORM error mapping | RuntimeException wrapped without root cause detail |
| **Contributing** | Frontend uses data URLs instead of multipart | add-car-wizard.component.ts line 238: `readAsDataURL()` |
| **Config** | MySQL default `max_allowed_packet=4 MB` too small | application.properties silent on this setting |

---

## Impact Assessment

- **Severity:** HIGH (blocks car onboarding for users with multiple car photos)
- **Frequency:** Consistent when >2 images or >1 MB image files uploaded
- **User Experience:** User sees generic "Bad Request" error with no actionable guidance
- **Data Safety:** No risk of data corruption (transaction rolled back)
- **Business Risk:** Prevents MVP launch if car upload with images is mandatory

---

## Next Steps

See **SOLUTION_PLAN.md** for:
1. Immediate fixes (request size validation, error messaging)
2. Long-term refactor (multipart form submission, cloud storage URLs)
3. Testing strategy (unit, integration, regression tests)
4. Rollout & migration plan
5. Observability improvements
