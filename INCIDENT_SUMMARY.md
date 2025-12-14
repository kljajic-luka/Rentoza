# Incident Summary: HTTP 400 "Data truncation" on POST /api/cars/add

## Quick Reference

**Incident:** Users cannot complete "Add Car" wizard when uploading car photos  
**Error:** HTTP 400 Bad Request with "could not execute statement" message  
**Root Cause:** Base64-encoded images in JSON payload exceed MySQL `max_allowed_packet` limit  
**Fix:** Phase 1 (immediate) + Phase 2 (refactor)  

---

## The Problem in 30 Seconds

1. **Frontend** converts images to base64 and embeds in JSON request body
2. **Base64 encoding** inflates data by ~33% (5 MB image → 6.7 MB encoded)
3. **Multiple images** accumulate: 2 × 5 MB images = 13.4 MB JSON
4. **MySQL default** `max_allowed_packet=4 MB` rejects the payload
5. **Result:** HTTP 400, "could not execute statement"

---

## Request Lifecycle

```
User selects 2 × 2 MB car photos in browser
    ↓
Frontend converts to data:image/jpeg;base64,... (now 5.3 MB total)
    ↓
JSON request body = 5.3 MB + other fields
    ↓
Spring multipart middleware accepts (max-request-size=10MB by default)
    ↓
CarRequestDTO validation: MISSING (no field size checks)
    ↓
CarService.addCar(): Tries to persist image data
    ↓
Hibernate ORM → INSERT statement with 5.3 MB base64 string
    ↓
MySQL rejects: "max_allowed_packet exceeded" (4 MB limit)
    ↓
DataIntegrityViolationException wrapped in RuntimeException
    ↓
CarController catches RuntimeException → HTTP 400 with generic message
    ↓
User sees: "Bad Request" (no guidance on images/size)
```

---

## Impact

- **Users affected:** Anyone uploading >2 images or images >2 MB (after base64)
- **Severity:** Blocks MVP car listing workflow
- **Data loss:** None (transaction rolled back)
- **Workaround:** None without code changes

---

## Phase 1 Solution (2-3 days)

### Backend
- ✅ Add `@Size` validators to CarRequestDTO image fields
- ✅ Add custom `@ValidImageList` validator for image arrays
- ✅ Add CarService validation logic
- ✅ Create GlobalExceptionHandler for clear error messages
- ✅ Increase MySQL `max_allowed_packet` from 4M → 16M

### Frontend
- ✅ Enforce 500 KB max per image (accounts for base64 overhead)
- ✅ Show user-friendly error messages (Serbian)
- ✅ Compress images before base64 encoding

### Result
- Users with normal image sizes (~2 MB uncompressed) → ✅ Works
- Users trying to upload massive images → ❌ Clear error message
- HTTP 400 truncation errors → ❌ Eliminated

---

## Phase 2 Solution (2-3 weeks)

Architectural refactor to eliminate base64 in JSON entirely:

- Switch to **multipart/form-data** (separate files from JSON)
- Integrate **AWS S3** or **Cloudinary** (cloud storage)
- Database stores only **URL references** (not binary data)
- Upload is **progressive** with chunking (optional)

**Result:** Zero base64 overhead, native file handling, CDN-ready.

---

## Files Changed (Phase 1)

### Backend
- `Rentoza/src/main/java/org/example/rentoza/car/dto/CarRequestDTO.java`
- `Rentoza/src/main/java/org/example/rentoza/car/dto/ImageListValidator.java` (new)
- `Rentoza/src/main/java/org/example/rentoza/car/CarService.java`
- `Rentoza/src/main/java/org/example/rentoza/car/CarController.java`
- `Rentoza/src/main/java/org/example/rentoza/exception/GlobalExceptionHandler.java` (new)
- `Rentoza/src/main/resources/application-dev.properties`
- `Rentoza/src/main/resources/application-prod.properties`

### Frontend
- `rentoza-frontend/src/app/features/owner/components/document-upload/document-upload.component.ts`
- `rentoza-frontend/src/app/features/owner/pages/add-car-wizard/add-car-wizard.component.ts`

### Database
- `/etc/mysql/mysql.conf.d/mysqld.cnf` (increase `max_allowed_packet`)

### Tests (New)
- `Rentoza/src/test/java/org/example/rentoza/car/CarRequestDTOValidationTest.java`
- `Rentoza/src/test/java/org/example/rentoza/car/CarControllerIntegrationTest.java`

---

## Validation Rules (Phase 1)

| Field | Constraint | Reason |
|-------|-----------|--------|
| `imageUrl` | ≤ 500 KB (base64) | Fits within MySQL packet limit |
| `imageUrls[]` | ≤ 10 images | Prevents array overflow |
| Each image | JPEG or PNG | Supported formats |
| Each image | Valid base64 format | Prevents injection |

---

## Configuration Changes

### MySQL (Server)
```ini
# OLD (default)
max_allowed_packet = 4M

# NEW (Phase 1)
max_allowed_packet = 16M

# NOTE: Phase 2 can reduce back to 4M once using cloud storage
```

### Spring Boot (Backend)
```properties
# application-dev.properties & application-prod.properties

# MySQL Connection
spring.datasource.url=jdbc:mysql://localhost:3306/rentoza?maxAllowedPacket=16M

# Multipart Upload
spring.servlet.multipart.max-request-size=16MB
spring.servlet.multipart.max-file-size=10MB
```

---

## Testing Checklist

### Unit Tests
- [ ] DTO validation rejects images > 500 KB
- [ ] DTO validation rejects > 10 images
- [ ] DTO validation rejects invalid image formats
- [ ] DTO validation accepts valid payloads

### Integration Tests
- [ ] Car creation with 5 × 500 KB images succeeds
- [ ] Car creation with 10 MB image fails with clear message
- [ ] GlobalExceptionHandler returns proper error responses
- [ ] Database does NOT save truncated data

### Regression Tests
- [ ] Original bug (5 MB image → HTTP 400) is fixed
- [ ] Error message guides user on image size limits
- [ ] No truncation errors in MySQL logs

### Manual Testing (QA)
- [ ] Upload car with 1 image (~2 MB) → ✅ Works
- [ ] Upload car with 5 images (~1 MB each) → ✅ Works
- [ ] Upload car with 10 images → ✅ Works
- [ ] Try to upload 15 MB image → ❌ Client error before submission
- [ ] Try to bypass frontend, send 10 MB via cURL → ❌ Backend validation error

---

## Known Limitations (Phase 1)

- Base64 still causes ~33% encoding overhead (Phase 2 removes this)
- Maximum images still limited to 10 (by design)
- Database stores base64 strings (not cloud references) – Phase 2 improves
- No CDN/caching for images (Phase 2 adds S3 + CloudFront)

---

## Monitoring & Observability

### Metrics to Track (Post-Deployment)

```
image.validation.failures (counter)
  - reason="oversized", "invalid_format", "too_many"
  - Should be < 5% of total requests

car.creation.success (counter)
  - imageCount="1", "2", "5", "10"
  - Should remain > 95% success rate

http.requests.400 (counter)
  - path="/api/cars/add"
  - Should decrease significantly after Phase 1

database.errors (counter)
  - type="data_truncation"
  - Should hit zero after Phase 1
```

### Logs to Monitor

```
[CAR_ADD] Received request for user: {email}
[IMAGE_VALIDATION] reason=oversized, estimatedSizeKB={size}
[CAR_CREATION] success, imageCount={count}
[DATABASE ERROR] Data integrity violation: {message}
```

---

## Rollback Procedure

If Phase 1 causes issues:

1. **Revert backend code** (5 min)
   - Remove validators, revert CarController
   - Restores old "Bad Request" behavior (acceptable for MVP)

2. **Revert frontend code** (2 min)
   - Remove image size checks
   - Users can again attempt uploads (fail server-side)

3. **Keep MySQL `max_allowed_packet=16M`** (non-breaking)
   - Reduces but doesn't eliminate truncation errors
   - Safe to leave in place

**No database migrations needed** – Phase 1 has zero DDL changes.

---

## Next Steps

### Immediate (Today)
- [ ] Review ROOT_CAUSE_ANALYSIS.md
- [ ] Review SOLUTION_PLAN.md
- [ ] Agree on Phase 1 scope & timeline

### Week 1 (Phase 1 Implementation)
- [ ] Backend developer: Implement validators & GlobalExceptionHandler
- [ ] Frontend developer: Add image compression & size checks
- [ ] DevOps: Configure MySQL `max_allowed_packet` in all environments
- [ ] QA: Prepare test cases

### Week 2 (Testing & Deployment)
- [ ] Run unit & integration tests
- [ ] Deploy to staging, smoke test
- [ ] Deploy to production
- [ ] Monitor metrics & logs

### Week 3-4 (Phase 2 Planning)
- [ ] Design multipart refactor
- [ ] Evaluate cloud storage options (S3 vs. Cloudinary)
- [ ] Create migration strategy for existing cars

---

## References

- **ROOT_CAUSE_ANALYSIS.md** – Detailed diagnosis with evidence
- **SOLUTION_PLAN.md** – Complete implementation guide with code examples
- **MySQL Documentation** – `max_allowed_packet`: https://dev.mysql.com/doc/refman/8.0/en/server-system-variables.html#sysvar_max_allowed_packet
- **Spring Boot Multipart Handling** – https://spring.io/guides/gs/uploading-files/

---

## Questions & Answers

**Q: Why not just increase `max_allowed_packet` to 100 MB?**  
A: Works short-term but doesn't solve the architectural issue. Phase 2 removes base64 entirely, reducing the limit back to 4 MB and improving performance.

**Q: Can we use gzip compression on request body?**  
A: Yes, but HTTP clients don't always support it. Better to eliminate base64 (Phase 2).

**Q: Why validate images twice (frontend + backend)?**  
A: **Defense in depth.** Frontend provides fast feedback; backend prevents bypass attacks.

**Q: Will Phase 2 break existing cars?**  
A: No. Existing cars with base64 images keep working. New cars use URLs. Migration script available post-deployment.

**Q: How long does Phase 1 implementation take?**  
A: 2-3 days (1-2 for code, 1 for testing).

**Q: Can we launch MVP with Phase 1 only?**  
A: Yes. Phase 1 unblocks MVP for normal-sized images. Phase 2 is long-term optimization.

---

**Document Version:** 1.0  
**Created:** 2025-12-14  
**Status:** Draft - Ready for Review  
**Owner:** Development Team
