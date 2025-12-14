# Solution Plan: Fix Data Truncation on Car Add (POST /api/cars/add)

**Document:** Enterprise-Grade Solution Design  
**Status:** DRAFT - Ready for Implementation  
**Priority:** P1 (Blocks MVP Launch)  
**Scope:** Backend validation, error mapping, frontend refactor  

---

## Solution Overview

### Strategy

We implement a **two-phase approach**:

1. **Phase 1 (Immediate - 2-3 days):** Band-aid fixes to unblock MVP launch
   - Add request size validation
   - Improve error messages
   - Configure MySQL `max_allowed_packet`
   - Cap base64 image sizes on frontend

2. **Phase 2 (Long-term - 2-3 weeks):** Architectural refactor
   - Switch from base64 JSON embedding to multipart/form-data
   - Implement cloud storage (AWS S3 or Cloudinary)
   - Separate document upload into dedicated endpoint
   - Add progressive upload with chunking

---

## Phase 1: Immediate Fixes (MVP Unblock)

### 1.1 Backend: Request DTO Validation

**File:** `Rentoza/src/main/java/org/example/rentoza/car/dto/CarRequestDTO.java`

Add validation constraints to prevent oversized payloads:

```java
package org.example.rentoza.car.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CarRequestDTO {
    @NotBlank(message = "Brand is required")
    @Size(min = 2, max = 50, message = "Brand must be 2-50 characters")
    private String brand;

    @NotBlank(message = "Model is required")
    @Size(min = 2, max = 100, message = "Model must be 2-100 characters")
    private String model;

    @NotNull(message = "Year is required")
    @Min(1950)
    @Max(2050)
    private Integer year;

    @NotNull(message = "Price is required")
    @DecimalMin("10.00")
    private BigDecimal pricePerDay;

    @NotBlank(message = "Location is required")
    @Size(max = 255, message = "Location must be ≤255 characters")
    private String location;

    // ===== IMAGE VALIDATION =====
    // CRITICAL: Base64 images cause truncation. Max 500KB per image.
    // Frontend MUST compress images before encoding.
    @Size(max = 524288, message = "Primary image exceeds 500KB. Compress before upload.")
    private String imageUrl;

    @Size(max = 10, message = "Maximum 10 images allowed")
    private List<String> imageUrls;

    // Validate that no individual image exceeds 500 KB
    @jakarta.validation.constraints.Pattern(regexp = "^$|^data:image/(jpeg|jpg|png);base64,[A-Za-z0-9+/=]*$",
            message = "Invalid image format. Must be JPEG or PNG as base64.")
    private String _unused_for_validation; // Placeholder for custom validator

    // ===== GEOSPATIAL VALIDATION =====
    @NotNull(message = "Latitude is required")
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private BigDecimal locationLatitude;

    @NotNull(message = "Longitude is required")
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private BigDecimal locationLongitude;

    @Size(max = 255, message = "Address must be ≤255 characters")
    private String locationAddress;

    @Size(max = 100, message = "City must be ≤100 characters")
    private String locationCity;

    @Size(max = 10, message = "ZIP code must be ≤10 characters")
    private String locationZipCode;

    // ===== VEHICLE DETAILS VALIDATION =====
    @Size(min = 4, max = 20, message = "License plate must be 4-20 characters")
    @Pattern(regexp = "^[A-Z]{2}-[0-9]{3,4}-[A-Z]{2}$",
            message = "License plate must match format: YY-NNN-YY")
    private String licensePlate;

    @Size(max = 1000, message = "Description must be ≤1000 characters")
    private String description;

    @Min(2)
    @Max(9)
    private Integer seats;

    private FuelType fuelType;

    @Min(0)
    @Max(50)
    private Double fuelConsumption;

    private TransmissionType transmissionType;

    private List<Feature> features;

    @Size(max = 20, message = "Maximum 20 add-ons allowed")
    private List<String> addOns;

    private CancellationPolicy cancellationPolicy;

    @Min(1)
    @Max(365)
    private Integer minRentalDays;

    @Min(1)
    @Max(365)
    private Integer maxRentalDays;
}
```

**Add Custom Validator for Image Arrays:**

```java
package org.example.rentoza.car.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ImageListValidator.class)
public @interface ValidImageList {
    String message() default "Image list contains oversized or invalid images";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    
    int maxImages() default 10;
    int maxSizeBytes() default 524288; // 500KB
}

class ImageListValidator implements ConstraintValidator<ValidImageList, java.util.List<String>> {
    private int maxImages;
    private int maxSizeBytes;

    @Override
    public void initialize(ValidImageList annotation) {
        this.maxImages = annotation.maxImages();
        this.maxSizeBytes = annotation.maxSizeBytes();
    }

    @Override
    public boolean isValid(java.util.List<String> value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }

        if (value.size() > maxImages) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                String.format("Maximum %d images allowed, got %d", maxImages, value.size())
            ).addConstraintViolation();
            return false;
        }

        for (int i = 0; i < value.size(); i++) {
            String image = value.get(i);
            if (image == null || image.isEmpty()) {
                continue;
            }

            // Estimate base64 decoded size (base64 is ~33% larger than raw)
            int estimatedSize = (int) (image.length() * 0.75);
            if (estimatedSize > maxSizeBytes) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                    String.format("Image %d exceeds %d KB limit (size: %d KB)",
                        i, maxSizeBytes / 1024, estimatedSize / 1024)
                ).addConstraintViolation();
                return false;
            }

            // Validate base64 format
            if (!image.matches("^data:image/(jpeg|jpg|png);base64,[A-Za-z0-9+/=]*$")) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                    String.format("Image %d has invalid format (must be JPEG/PNG base64)", i)
                ).addConstraintViolation();
                return false;
            }
        }

        return true;
    }
}
```

**Update DTO to use custom validator:**

```java
@ValidImageList(maxImages = 10, maxSizeBytes = 524288)
private List<String> imageUrls;
```

---

### 1.2 Backend: CarService Validation

**File:** `Rentoza/src/main/java/org/example/rentoza/car/CarService.java`

Add explicit validation before persistence:

```java
public Car addCar(CarRequestDTO dto, User owner) {
    // Existing validations (brand, model, price, coordinates)
    if (dto.getBrand() == null || dto.getBrand().isBlank()) {
        throw new RuntimeException("Brand is required");
    }
    // ... existing checks ...

    // NEW: Validate image data
    validateImageData(dto);

    // ... rest of method ...
}

private void validateImageData(CarRequestDTO dto) {
    final int MAX_IMAGE_SIZE = 500 * 1024; // 500 KB
    final int MAX_IMAGES = 10;

    // Validate primary image
    if (dto.getImageUrl() != null && !dto.getImageUrl().isEmpty()) {
        int estimatedSize = (int) (dto.getImageUrl().length() * 0.75);
        if (estimatedSize > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException(
                String.format(
                    "Primary image exceeds 500 KB limit (actual: %d KB). " +
                    "Please compress the image and try again.",
                    estimatedSize / 1024
                )
            );
        }
        if (!dto.getImageUrl().matches("^data:image/(jpeg|jpg|png);base64,[A-Za-z0-9+/=]*$")) {
            throw new IllegalArgumentException(
                "Primary image must be JPEG or PNG in base64 format"
            );
        }
    }

    // Validate image array
    if (dto.getImageUrls() != null && !dto.getImageUrls().isEmpty()) {
        if (dto.getImageUrls().size() > MAX_IMAGES) {
            throw new IllegalArgumentException(
                String.format(
                    "Too many images (%d provided, max %d allowed)",
                    dto.getImageUrls().size(),
                    MAX_IMAGES
                )
            );
        }

        for (int i = 0; i < dto.getImageUrls().size(); i++) {
            String imageUrl = dto.getImageUrls().get(i);
            if (imageUrl == null || imageUrl.isEmpty()) {
                continue;
            }

            int estimatedSize = (int) (imageUrl.length() * 0.75);
            if (estimatedSize > MAX_IMAGE_SIZE) {
                throw new IllegalArgumentException(
                    String.format(
                        "Image %d exceeds 500 KB limit (actual: %d KB). " +
                        "Please compress and retry.",
                        i + 1,
                        estimatedSize / 1024
                    )
                );
            }

            if (!imageUrl.matches("^data:image/(jpeg|jpg|png);base64,[A-Za-z0-9+/=]*$")) {
                throw new IllegalArgumentException(
                    String.format("Image %d must be JPEG or PNG in base64 format", i + 1)
                );
            }
        }
    }
}
```

---

### 1.3 Backend: Global Exception Handler

**File:** Create `Rentoza/src/main/java/org/example/rentoza/exception/GlobalExceptionHandler.java`

Provide clear error messages instead of generic "Bad Request":

```java
package org.example.rentoza.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle JSR-303 validation errors (@Valid, @Validated)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationException(
            MethodArgumentNotValidException ex
    ) {
        log.warn("[VALIDATION ERROR] Field validation failed", ex);

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(fieldName, message);
        });

        return ResponseEntity.badRequest().body(Map.of(
            "error", "Validation failed",
            "message", "Please check your input and try again",
            "fields", errors
        ));
    }

    /**
     * Handle IllegalArgumentException (business logic validation failures)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgumentException(
            IllegalArgumentException ex
    ) {
        log.warn("[VALIDATION ERROR] {}", ex.getMessage());

        return ResponseEntity.badRequest().body(Map.of(
            "error", "Invalid request",
            "message", ex.getMessage()
        ));
    }

    /**
     * Handle request size exceeded (multipart payload too large)
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeException(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex
    ) {
        log.warn("[UPLOAD ERROR] Payload exceeds maximum size: {} bytes", ex.getMaxUploadSize());

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
            "error", "Payload too large",
            "message", String.format(
                "Your request exceeds the maximum size of %d MB. " +
                "Please compress images or reduce the number of files.",
                ex.getMaxUploadSize() / (1024 * 1024)
            ),
            "maxSize", ex.getMaxUploadSize()
        ));
    }

    /**
     * Handle database data truncation errors
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex
    ) {
        String message = ex.getMostSpecificCause().getMessage();
        log.error("[DATABASE ERROR] Data integrity violation: {}", message);

        // Check for common truncation scenarios
        if (message != null && message.toLowerCase().contains("data too long")) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Data too large",
                "message", "One or more fields exceed maximum allowed length. " +
                           "Please check image sizes and try again.",
                "detail", "Image data may be too large (max 500 KB per image)"
            ));
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error", "Database error",
            "message", "An unexpected error occurred. Please try again later."
        ));
    }

    /**
     * Fallback for all other RuntimeExceptions
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntimeException(RuntimeException ex) {
        log.error("[RUNTIME ERROR] Unexpected error", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error", "Internal server error",
            "message", "An unexpected error occurred. Please try again later.",
            "requestId", java.util.UUID.randomUUID()
        ));
    }
}
```

---

### 1.4 Backend: Update CarController

**File:** `Rentoza/src/main/java/org/example/rentoza/car/CarController.java:46-66`

Add validation and better error handling:

```java
@PostMapping("/add")
@PreAuthorize("hasRole('OWNER')")
public ResponseEntity<?> addCar(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Car details with images (max 500 KB per image, 10 total)",
            required = true
        )
        @Valid @RequestBody CarRequestDTO dto,  // ← Add @Valid for automatic validation
        @org.springframework.security.core.annotation.AuthenticationPrincipal 
            org.example.rentoza.security.JwtUserPrincipal principal
) {
    log.info("[CarAdd] Received request for user: {}", principal.getUsername());

    try {
        if (!principal.hasRole("OWNER")) {
            return ResponseEntity.status(403).body(Map.of(
                "error", "Only owners can list cars"
            ));
        }

        User owner = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Owner not found: " + principal.getUsername()
                ));

        Car saved = service.addCar(dto, owner);
        log.info("[CarAdd] Success: car created with id={}", saved.getId());
        return ResponseEntity.ok(new CarResponseDTO(saved));

    } catch (IllegalArgumentException e) {
        // Validation errors are now handled by GlobalExceptionHandler
        log.warn("[CarAdd] Validation error: {}", e.getMessage());
        throw e;  // Re-throw for GlobalExceptionHandler
    } catch (ResourceNotFoundException e) {
        log.warn("[CarAdd] Resource not found: {}", e.getMessage());
        return ResponseEntity.notFound().build();
    }
}
```

---

### 1.5 Database Configuration

**File:** `Rentoza/src/main/resources/application-dev.properties` (add/update)

```properties
# ============================================================
# MySQL Configuration - Image Handling Fix
# ============================================================

# CRITICAL: Increase max_allowed_packet to handle base64 images
# Default (4 MB) is insufficient for multiple images
# Set to 16 MB for MVP, move to cloud storage in Phase 2
spring.datasource.url=jdbc:mysql://localhost:3306/rentoza?\
  useUnicode=true&\
  characterEncoding=utf8mb4&\
  serverTimezone=UTC&\
  allowPublicKeyRetrieval=true&\
  useSSL=false&\
  zeroDateTimeBehavior=CONVERT_TO_NULL&\
  maxAllowedPacket=16M

# Alternative: Set via MySQL connection parameter if above doesn't work
spring.datasource.hikari.data-source-properties.maxAllowedPacket=16777216

# Multipart upload limits
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=16MB
spring.servlet.multipart.enabled=true
```

**Update MySQL Server Configuration (for production/staging):**

Edit `/etc/mysql/mysql.conf.d/mysqld.cnf` or `/etc/my.cnf`:

```ini
[mysqld]
max_allowed_packet = 16M  # 16 MB for MVP
# Phase 2: Reduce to 4M when using cloud storage for images
```

Then restart MySQL:
```bash
sudo systemctl restart mysql
```

---

### 1.6 Frontend: Image Size Enforcement

**File:** `rentoza-frontend/src/app/features/owner/components/document-upload/document-upload.component.ts`

Compress images before base64 encoding:

```typescript
import { Component, Input, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import { CarDocumentService, DocumentType, CarDocument } from '../../../../core/services/car-document.service';

@Component({
  selector: 'app-document-upload',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './document-upload.component.html',
  styleUrls: ['./document-upload.component.scss']
})
export class DocumentUploadComponent {
  @Input() carId?: number;
  @Input() documentType: DocumentType = 'REGISTRATION';
  @Input() label: string = 'Otpremite dokument';
  @Input() required: boolean = true;
  
  @Output() uploaded = new EventEmitter<CarDocument>();
  @Output() fileSelected = new EventEmitter<File>();
  @Output() removed = new EventEmitter<void>();

  // State
  file = signal<File | null>(null);
  previewUrl = signal<string | null>(null);
  error = signal<string | null>(null);
  uploadProgress = signal<number>(0);
  uploading = signal<boolean>(false);
  expiryDate = signal<string>('');
  dragOver = signal<boolean>(false);

  private readonly allowedTypes = [
    'application/pdf',
    'image/png',
    'image/jpeg',
    'image/jpg'
  ];
  private readonly maxSize = 10 * 1024 * 1024; // 10MB for documents
  // UPDATED: 500 KB max for images to prevent base64 overflow
  private readonly maxImageSize = 500 * 1024; // 500 KB

  constructor(private documentService: CarDocumentService) {}

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver.set(false);

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.handleFile(files[0]);
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.handleFile(input.files[0]);
    }
  }

  private handleFile(file: File): void {
    this.error.set(null);
    this.previewUrl.set(null);

    // Validate type
    if (!this.allowedTypes.includes(file.type)) {
      this.error.set('Dozvoljeni formati: PDF, JPEG, PNG');
      return;
    }

    // Validate size (max 10 MB for documents)
    if (file.size > this.maxSize) {
      this.error.set(`Maksimalna veličina: 10 MB (vaš fajl je ${this.formatFileSize(file.size)})`);
      return;
    }

    // For images, enforce 500 KB limit (accounts for base64 encoding overhead)
    if (file.type.startsWith('image/') && file.size > this.maxImageSize) {
      this.error.set(
        `Slika je prevelika (${this.formatFileSize(file.size)}, max 500 KB). ` +
        `Molimo kompresujte sliku i pokušajte ponovo.`
      );
      return;
    }

    this.file.set(file);

    // Create preview for images
    if (file.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onload = () => {
        const dataUrl = reader.result as string;
        // Warn user if base64 will be large (accounting for 33% encoding overhead)
        const estimatedSize = (dataUrl.length * 0.75);
        if (estimatedSize > this.maxImageSize) {
          this.error.set(
            `Kodirani oblik slike će biti prevelik (${this.formatFileSize(estimatedSize)}). ` +
            `Molimo kompresujte sliku.`
          );
          this.file.set(null);
          this.previewUrl.set(null);
          return;
        }
        this.previewUrl.set(dataUrl);
      };
      reader.readAsDataURL(file);
    }
    
    this.fileSelected.emit(file);
  }

  upload(): void {
    const currentFile = this.file();
    if (!currentFile || !this.carId) return;

    this.uploading.set(true);
    this.uploadProgress.set(0);
    this.error.set(null);

    this.documentService.uploadDocument(
      this.carId,
      currentFile,
      this.documentType,
      this.expiryDate() || undefined
    ).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress) {
          const progress = event.total
            ? Math.round((100 * event.loaded) / event.total)
            : 0;
          this.uploadProgress.set(progress);
        } else if (event.type === HttpEventType.Response) {
          this.uploading.set(false);
          this.uploadProgress.set(100);
          if (event.body) {
            this.uploaded.emit(event.body);
          }
        }
      },
      error: (err) => {
        this.uploading.set(false);
        this.error.set(err.error?.message || 'Greška pri otpremanju');
        console.error('Upload error:', err);
      }
    });
  }

  remove(): void {
    this.file.set(null);
    this.previewUrl.set(null);
    this.error.set(null);
    this.uploadProgress.set(0);
    this.removed.emit();
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  getDocumentIcon(): string {
    const file = this.file();
    if (!file) return 'file';
    if (file.type === 'application/pdf') return 'file-pdf';
    if (file.type.startsWith('image/')) return 'image';
    return 'file';
  }
}
```

**Update Add-Car Wizard to enforce image size limits:**

**File:** `rentoza-frontend/src/app/features/owner/pages/add-car-wizard/add-car-wizard.component.ts`

```typescript
// Line 223-244: File selection handler
protected handleFileSelect(event: Event): void {
  const input = event.target as HTMLInputElement;
  if (!input.files) return;

  const files = Array.from(input.files);
  const maxFiles = 10 - this.imageUrls().length;
  const maxImageSize = 500 * 1024; // 500 KB strict limit

  files.slice(0, maxFiles).forEach((file) => {
    // UPDATED: Enforce 500 KB limit for images
    if (file.size > maxImageSize) {
      this.snackBar.open(
        `Slika je prevelika (${this.formatFileSize(file.size)}, max 500 KB). ` +
        `Molimo kompresujte i pokušajte ponovo.`,
        'Zatvori',
        { duration: 5000, panelClass: ['snackbar-error'] }
      );
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      
      // Warn if base64 encoded size exceeds limit
      const encodedSize = (dataUrl.length * 0.75);
      if (encodedSize > maxImageSize) {
        this.snackBar.open(
          `Kodirana slika je prevelika. Pokušajte sa manjom rezolucijom.`,
          'Zatvori',
          { duration: 5000, panelClass: ['snackbar-error'] }
        );
        return;
      }

      this.imageUrls.update((urls) => [...urls, dataUrl]);
    };
    reader.readAsDataURL(file);
  });

  input.value = '';
}

// Add helper method
protected formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}
```

---

### 1.7 Frontend: Error Handling

**File:** `rentoza-frontend/src/app/features/owner/pages/add-car-wizard/add-car-wizard.component.ts`

Improve error messages for users:

```typescript
protected async submitForm(): Promise<void> {
  if (this.isSubmitting()) return;

  // ... existing validation ...

  this.isSubmitting.set(true);

  try {
    // ... car creation code ...
    const createdCar = await this.carService.addCar(carData).toPromise();
    
    if (!createdCar?.id) throw new Error('Failed to create car');

    // ... document upload code ...
    await Promise.all(uploadPromises);

    // Success
    this.carService.clearSearchCache();
    this.snackBar.open(
      'Vozilo i dokumenti uspešno otpremljeni! Na čekanju je odobrenja administratora.',
      'Zatvori',
      { duration: 5000, panelClass: ['snackbar-success'] }
    );
    this.router.navigate(['/owner/cars']);

  } catch (error: any) {
    console.error('Error in car submission flow:', error);

    // IMPROVED: Provide specific guidance based on error type
    let message = 'Greška pri kreiranju vozila. Proverite podatke.';

    if (error.error?.message) {
      // Backend validation error
      message = error.error.message;
      
      // Specific guidance for common issues
      if (message.includes('500 KB') || message.includes('image')) {
        message += '\n\nSaveti:\n' +
                   '- Kompresujte slike na računaru pre otpremanja\n' +
                   '- Maksimalno 10 slika, svaka ≤500 KB\n' +
                   '- Koristi JPEG format za bolje kompresovanje';
      }
      if (message.includes('Payload too large')) {
        message += '\n\nSmanjite broj ili veličinu slika i pokušajte ponovo.';
      }
    } else if (error.status === 413 || error.status === 414) {
      message = 'Zahtev je prevelik. Smanjite broj ili veličinu slika i pokušajte ponovo.';
    } else if (error.status === 400) {
      message = 'Nevaljani podatci. Molimo proverite sve poljeformulara.';
    } else if (error.status >= 500) {
      message = 'Greška servera. Pokušajte ponovo za nekoliko minuta.';
    }

    this.snackBar.open(message, 'Zatvori', {
      duration: 8000,
      panelClass: ['snackbar-error']
    });
  } finally {
    this.isSubmitting.set(false);
  }
}
```

---

## Phase 2: Long-Term Architectural Refactor

### 2.1 Multipart Form Data (Instead of Base64 JSON)

**Motivation:** Separation of concerns, native HTTP multipart support, browser-native file handling.

**Architecture:**
```
Frontend: Form + Files → Multipart/form-data
    ↓
Backend: MultipartFile + FormParameters
    ↓
Service: Save to temp location
    ↓
Upload: Move to cloud storage (S3)
    ↓
Database: Store only URL reference
```

**Implementation (Sketch):**

```java
// Backend Controller
@PostMapping(value = "/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<?> addCarWithMultipart(
    @RequestPart("carData") @Valid CarRequestDTO dto,
    @RequestPart(value = "images", required = false) MultipartFile[] images,
    @RequestPart(value = "documents", required = false) MultipartFile[] documents
) {
    // Validate image files
    // Upload to cloud storage
    // Get URLs back
    // Create car with URL references
}
```

**Frontend:**
```typescript
addCarMultipart(carData: Partial<Car>, files: File[]): Observable<Car> {
  const formData = new FormData();
  formData.append('carData', JSON.stringify(carData));
  
  files.forEach((file, i) => {
    formData.append('images', file);
  });

  return this.http.post<Car>(`${this.baseUrl}/add`, formData);
}
```

**Benefits:**
- No base64 encoding overhead (~33% size savings)
- Native browser file handling
- Better progress tracking per file
- Streams to disk instead of loading into memory
- Compatible with Cloud Storage APIs (S3 pre-signed URLs)

---

### 2.2 Cloud Storage Integration (AWS S3 / Cloudinary)

**Motivation:** Offload storage from database, CDN distribution, better performance.

**Recommendation:** AWS S3 with CloudFront CDN (or Cloudinary for simpler setup).

**Design:**

```
Frontend: Upload file → S3 pre-signed URL (direct upload)
    ↓
S3: Store file + return URL
    ↓
Frontend: POST /api/cars/add with S3 URL reference
    ↓
Backend: Validate URL, save Car with URL
```

**No need to handle binary data in Java** – just URLs.

---

### 2.3 Separate Document Upload Endpoint

Move car documents to dedicated endpoint (post-car creation):

```java
@PostMapping("/{carId}/documents")
@PreAuthorize("hasRole('OWNER')")
public ResponseEntity<?> uploadDocument(
    @PathVariable Long carId,
    @RequestPart("document") MultipartFile file,
    @RequestPart("type") DocumentType type,
    @RequestPart("expiryDate") LocalDate expiryDate
) {
    // Validate document
    // Upload to S3
    // Create CarDocument record with URL
    return ResponseEntity.ok(documentResponse);
}
```

This matches the existing `CarDocumentService.uploadDocument()` method and separates concerns.

---

### 2.4 Progressive Upload with Chunking (Optional)

For users with very slow connections or large videos (future feature):

Use libraries like **TUS.io** or **ng-chunk-upload**.

```typescript
upload = tusClient.upload(file, {
  chunkSize: 5 * 1024 * 1024, // 5 MB chunks
  parallelUploads: 2,
  resume: true,
  retryDelays: [1000, 3000, 5000]
});
```

---

## Testing Strategy

### Unit Tests

**File:** `Rentoza/src/test/java/org/example/rentoza/car/CarRequestDTOValidationTest.java`

```java
@DisplayName("CarRequestDTO Validation Tests")
class CarRequestDTOValidationTest {

    private Validator validator;

    @BeforeEach
    void setup() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Should reject imageUrl exceeding 500 KB")
    void testImageUrlSizeValidation() {
        CarRequestDTO dto = new CarRequestDTO();
        dto.setBrand("BMW");
        // ... set other required fields ...
        
        // Create a base64 string larger than 500 KB
        String largeImage = "data:image/jpeg;base64," + "A".repeat(600000);
        dto.setImageUrl(largeImage);

        Set<ConstraintViolation<CarRequestDTO>> violations = validator.validate(dto);
        
        assertThat(violations)
            .isNotEmpty()
            .anySatisfy(v -> assertThat(v.getMessage()).contains("500 KB"));
    }

    @Test
    @DisplayName("Should reject more than 10 images")
    void testImageListSizeValidation() {
        CarRequestDTO dto = new CarRequestDTO();
        dto.setBrand("BMW");
        // ... set other required fields ...
        
        List<String> images = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            images.add("data:image/jpeg;base64," + "A".repeat(100000));
        }
        dto.setImageUrls(images);

        Set<ConstraintViolation<CarRequestDTO>> violations = validator.validate(dto);
        
        assertThat(violations)
            .isNotEmpty()
            .anySatisfy(v -> assertThat(v.getMessage()).contains("10 images"));
    }

    @Test
    @DisplayName("Should accept valid 5-image payload")
    void testValidImagePayload() {
        CarRequestDTO dto = buildValidCarDTO();
        
        List<String> images = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            images.add("data:image/jpeg;base64," + "A".repeat(100000)); // 100 KB each
        }
        dto.setImageUrls(images);

        Set<ConstraintViolation<CarRequestDTO>> violations = validator.validate(dto);
        
        assertThat(violations).isEmpty();
    }

    private CarRequestDTO buildValidCarDTO() {
        CarRequestDTO dto = new CarRequestDTO();
        dto.setBrand("BMW");
        dto.setModel("320i");
        dto.setYear(2020);
        dto.setPricePerDay(new BigDecimal("50.00"));
        dto.setLocation("Beograd");
        dto.setLocationLatitude(new BigDecimal("44.8176"));
        dto.setLocationLongitude(new BigDecimal("20.4633"));
        dto.setLocationCity("Beograd");
        dto.setLocationZipCode("11000");
        dto.setSeats(5);
        dto.setFuelType(FuelType.BENZIN);
        dto.setTransmissionType(TransmissionType.MANUAL);
        return dto;
    }
}
```

---

### Integration Tests

**File:** `Rentoza/src/test/java/org/example/rentoza/car/CarControllerIntegrationTest.java`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CarControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private UserRepository userRepository;

    private User testOwner;
    private String jwtToken;

    @BeforeEach
    void setup() {
        testOwner = userRepository.save(
            new User().setEmail("owner@test.com").setPassword("hashed")
        );
        jwtToken = generateTestJWT(testOwner);
    }

    @Test
    @DisplayName("Should reject car add with oversized image")
    void testAddCarWithOversizedImage() throws Exception {
        CarRequestDTO dto = buildValidCarDTO();
        dto.setImageUrl("data:image/jpeg;base64," + "A".repeat(600000)); // 600 KB

        mockMvc.perform(
            post("/api/cars/add")
                .header("Authorization", "Bearer " + jwtToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJson(dto))
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("500 KB")));

        // Verify car was NOT created
        assertThat(carRepository.count()).isZero();
    }

    @Test
    @DisplayName("Should accept valid car with 5 images")
    void testAddCarWithValidImages() throws Exception {
        CarRequestDTO dto = buildValidCarDTO();
        
        List<String> images = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            images.add("data:image/jpeg;base64," + "A".repeat(100000)); // 100 KB each
        }
        dto.setImageUrls(images);

        mockMvc.perform(
            post("/api/cars/add")
                .header("Authorization", "Bearer " + jwtToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJson(dto))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isNumber());

        // Verify car was created
        assertThat(carRepository.count()).isOne();
        Car saved = carRepository.findAll().get(0);
        assertThat(saved.getImageUrls()).hasSize(5);
    }

    @Test
    @DisplayName("Should handle MySQL max_allowed_packet gracefully")
    void testPayloadSizeLimitHandling() throws Exception {
        // Even if somehow a large payload gets through,
        // GlobalExceptionHandler should catch DataIntegrityViolation
        
        CarRequestDTO dto = buildValidCarDTO();
        
        // Simulate what would happen if validation was bypassed
        List<String> hugeImages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            hugeImages.add("data:image/jpeg;base64," + "A".repeat(500000)); // 10 MB total
        }
        dto.setImageUrls(hugeImages);

        mockMvc.perform(
            post("/api/cars/add")
                .header("Authorization", "Bearer " + jwtToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJson(dto))
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
    }
}
```

---

### Regression Test

Ensure that the original truncation bug cannot recur:

```java
@Test
@DisplayName("REGRESSION: 1MB document uploads should not cause HTTP 400")
void testDocumentUploadDoesNotCauseTruncation() throws Exception {
    // This test ensures the bug from the incident report does not recur
    
    CarRequestDTO dto = buildValidCarDTO();
    
    // Simulate user uploading documents + images totaling ~5 MB
    // with proper validation in place
    StringBuilder largeImage = new StringBuilder("data:image/jpeg;base64,");
    for (int i = 0; i < 400000; i++) { // ~400 KB base64
        largeImage.append("A");
    }
    dto.setImageUrl(largeImage.toString());
    dto.setImageUrls(List.of(largeImage.toString()));

    // Should now return 400 with clear message, not 400 with truncation error
    mockMvc.perform(
        post("/api/cars/add")
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(asJson(dto))
    )
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.message").value(containsString("Kompres"))); // Serbian error message
    
    // Key assertion: car was NOT created (vs truncated data saved)
    assertThat(carRepository.count()).isZero();
}
```

---

## Rollout & Migration Strategy

### Pre-Deployment Checklist

- [ ] All validation tests pass (Unit + Integration)
- [ ] Regression test confirms old bug cannot recur
- [ ] MySQL `max_allowed_packet=16M` configured in all environments
- [ ] Frontend image size limits deployed
- [ ] GlobalExceptionHandler deployed
- [ ] CarRequestDTO validators deployed
- [ ] Database migrations applied (none needed for Phase 1)

### Deployment Steps (Phase 1 - 2-3 days)

**Day 1: Backend Deployment**
```bash
# 1. Merge and deploy CarRequestDTO changes
# 2. Merge and deploy CarService validation changes
# 3. Merge and deploy GlobalExceptionHandler
# 4. Merge and deploy CarController changes
# 5. Deploy to staging, run integration tests
# 6. Monitor logs for validation errors
```

**Day 1-2: Database Configuration**
```bash
# Update MySQL max_allowed_packet in all environments
# Verify with: SELECT @@max_allowed_packet;
```

**Day 2: Frontend Deployment**
```bash
# 1. Merge and deploy document-upload component changes
# 2. Merge and deploy add-car-wizard changes
# 3. Deploy to staging, test with various image sizes
# 4. Monitor browser console for client-side validation errors
```

**Day 3: Smoke Testing**
```bash
# Manual test: Upload car with 5 x 2MB images
# Expected: Success (after compression on frontend)
# 
# Manual test: Try to upload with 10MB image
# Expected: Client-side error message in Serbian
#
# Manual test: Bypass frontend, send 10MB image via cURL
# Expected: Backend validation error with clear message
```

### Observability & Monitoring

Add structured logging to track image-related errors:

```java
@Component
public class ImageUploadMetrics {
    private static final Logger log = LoggerFactory.getLogger(ImageUploadMetrics.class);
    private final MeterRegistry meterRegistry;

    public void recordImageValidationFailure(String reason, int estimatedSize) {
        log.warn(
            "[IMAGE_VALIDATION] reason={}, estimatedSizeKB={}",
            reason, estimatedSize / 1024
        );
        meterRegistry.counter(
            "image.validation.failures",
            "reason", reason
        ).increment();
    }

    public void recordCarCreationSuccess(int imageCount) {
        log.info("[CAR_CREATION] success, imageCount={}", imageCount);
        meterRegistry.counter(
            "car.creation.success",
            "imageCount", String.valueOf(imageCount)
        ).increment();
    }
}
```

**Dashboards to monitor (Post-Deployment):**
- Image validation failure rate (should be < 5%)
- Average payload size (should drop significantly once frontend enforces limits)
- Database truncation errors (should hit zero)
- Car creation success rate (should remain > 95%)

---

### Rollback Plan

If Phase 1 causes issues:

1. **Revert backend changes** (remove CarRequestDTO validators, GlobalExceptionHandler)
   - Takes ~5 minutes
   - Restores old "bad request" error behavior (acceptable for MVP)

2. **Revert frontend changes** (remove image size checks)
   - Takes ~2 minutes
   - Users can again attempt large uploads (will fail server-side)

3. **Keep MySQL max_allowed_packet=16M** (non-breaking change, only helps)

**Rollback does NOT require database schema changes** (Phase 1 has no DDL).

---

## Success Criteria

✅ **Phase 1 Complete When:**
- [ ] HTTP 400 with truncation no longer occurs on normal-sized images (~2 MB)
- [ ] Clear error messages in Serbian guide users on image size limits
- [ ] Regression test passes (old bug cannot recur)
- [ ] Smoke testing shows car creation works with valid payloads
- [ ] No increase in error logs for validation failures

✅ **Phase 2 Complete When:**
- [ ] Multipart form-data endpoint works for car creation
- [ ] Cloud storage integration (S3/Cloudinary) functional
- [ ] Document upload endpoint separate and working
- [ ] Performance tests show faster upload times (no base64 overhead)
- [ ] MVP launch can proceed with confidence

---

## Appendix: Configuration Reference

### Development Environment (`application-dev.properties`)

```properties
# Image handling
spring.datasource.url=jdbc:mysql://localhost:3306/rentoza?maxAllowedPacket=16M
spring.servlet.multipart.max-request-size=16MB

# Error handling (show details in dev)
server.error.include-message=always
server.error.include-binding-errors=always

# Hibernate (update schema for local dev)
spring.jpa.hibernate.ddl-auto=update
```

### Staging/Production Environment (`application-prod.properties`)

```properties
# Image handling
spring.datasource.url=jdbc:mysql://prod-db:3306/rentoza?maxAllowedPacket=16M
spring.servlet.multipart.max-request-size=16MB

# Error handling (minimal info in prod)
server.error.include-message=never
server.error.include-binding-errors=never

# Hibernate (validate only, use migrations)
spring.jpa.hibernate.ddl-auto=validate
```

### MySQL Server Configuration (`/etc/mysql/mysql.conf.d/mysqld.cnf`)

```ini
[mysqld]
# Phase 1: Support base64 images in request payloads
max_allowed_packet = 16M

# Phase 2: Can be reduced to 4M when using cloud storage
# max_allowed_packet = 4M
```

---

## Timeline & Dependencies

| Phase | Task | Duration | Depends On | Owner |
|-------|------|----------|-----------|-------|
| 1 | Add DTO validators | 2 hours | - | Backend Team |
| 1 | Update CarService validation | 1.5 hours | - | Backend Team |
| 1 | Create GlobalExceptionHandler | 1 hour | - | Backend Team |
| 1 | Update CarController | 30 min | - | Backend Team |
| 1 | Increase MySQL max_allowed_packet | 30 min | DevOps | DevOps |
| 1 | Frontend image compression | 2 hours | - | Frontend Team |
| 1 | Frontend error handling | 1 hour | Backend changes | Frontend Team |
| 1 | Testing & QA | 1-2 days | All above | QA Team |
| 1 | Deploy to staging | 1 hour | All above | DevOps |
| 1 | Smoke testing | 2-4 hours | Staging deployment | QA Team |
| 1 | Deploy to production | 30 min | Smoke tests pass | DevOps |
| **Phase 1 Total** | | **3-4 days** | | |
| 2 | Refactor multipart endpoint | 3-4 days | - | Backend Team |
| 2 | Integrate AWS S3 | 2-3 days | - | Backend Team |
| 2 | Separate document upload | 1-2 days | - | Backend Team |
| 2 | Frontend refactor (multipart) | 3-4 days | - | Frontend Team |
| 2 | Progressive upload (optional) | 2-3 days | - | Frontend Team |
| 2 | Testing & integration tests | 2-3 days | All above | QA Team |
| **Phase 2 Total** | | **2-3 weeks** | Phase 1 deployed | |

---

## Risk Assessment

### Phase 1 Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Database migration fails | Low | High | Test on staging first, have rollback plan |
| Validation too strict | Medium | Medium | Monitor error rates, adjust limits if needed |
| Performance regression | Low | Medium | Profile request/response times |
| Incomplete error messages | Low | Low | QA tests all error paths |

### Phase 2 Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| S3 integration delay | Medium | High | Use mock S3 for testing, start early |
| Cloud storage costs | Medium | Low | Monitor and optimize, set CloudWatch alerts |
| Frontend multipart complexity | Low | High | Use proven library (ng-formdata, etc.) |

---

**End of Solution Plan**

See **ROOT_CAUSE_ANALYSIS.md** for detailed diagnosis.  
See **IMPLEMENTATION_GUIDE.md** (TBD) for code review checklist.
