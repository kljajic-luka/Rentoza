# Phase 1 Implementation Checklist

This is a step-by-step guide for implementing the Phase 1 fixes. Each item includes the exact file path and code changes needed.

---

## Backend Changes (Est. 4-5 hours)

### ✅ Step 1: Update CarRequestDTO with Validators
**File:** `Rentoza/src/main/java/org/example/rentoza/car/dto/CarRequestDTO.java`

**Status:** TODO  
**Estimated Time:** 30 min  
**Complexity:** Low

**Changes:**
1. Add `@Size` annotation to `brand`, `model`, `location`, `licensePlate`, `description`
2. Add `@Size` and custom `@ValidImageList` to `imageUrl` and `imageUrls`
3. Add `@DecimalMin`/`@DecimalMax` to price and geospatial fields
4. Import Jakarta Validation annotations

**Reference:** See SOLUTION_PLAN.md § 1.1

---

### ✅ Step 2: Create ImageListValidator
**File:** `Rentoza/src/main/java/org/example/rentoza/car/dto/ImageListValidator.java` (NEW)

**Status:** TODO  
**Estimated Time:** 45 min  
**Complexity:** Medium

**What to Create:**
1. New constraint annotation `@ValidImageList`
2. Validator implementation class `ImageListValidator`
3. Validates max image count (10)
4. Validates max size per image (500 KB decoded)
5. Validates base64 format (data:image/jpeg or png;base64,...)

**Reference:** See SOLUTION_PLAN.md § 1.1

---

### ✅ Step 3: Update CarService with Image Validation
**File:** `Rentoza/src/main/java/org/example/rentoza/car/CarService.java`

**Status:** TODO  
**Estimated Time:** 1 hour  
**Complexity:** Low

**Changes:**
1. Add `validateImageData(CarRequestDTO dto)` method
2. Check each image size (max 500 KB when decoded)
3. Check image format (valid base64 data:image/...)
4. Throw `IllegalArgumentException` with user-friendly message

**Reference:** See SOLUTION_PLAN.md § 1.2

---

### ✅ Step 4: Create GlobalExceptionHandler
**File:** `Rentoza/src/main/java/org/example/rentoza/exception/GlobalExceptionHandler.java` (NEW)

**Status:** TODO  
**Estimated Time:** 1.5 hours  
**Complexity:** Medium

**What to Create:**
1. `@RestControllerAdvice` class
2. Handler for `MethodArgumentNotValidException` (JSR-303 validation)
3. Handler for `IllegalArgumentException` (business logic errors)
4. Handler for `MaxUploadSizeExceededException` (large payloads)
5. Handler for `DataIntegrityViolationException` (database truncation)
6. Generic `RuntimeException` fallback
7. Return JSON with clear error messages

**Reference:** See SOLUTION_PLAN.md § 1.3

---

### ✅ Step 5: Update CarController
**File:** `Rentoza/src/main/java/org/example/rentoza/car/CarController.java:46-66`

**Status:** TODO  
**Estimated Time:** 30 min  
**Complexity:** Low

**Changes:**
1. Add `@Valid` annotation to `@RequestBody CarRequestDTO`
2. Update exception handling to re-throw `IllegalArgumentException`
3. Add logging for car creation (success/failure)
4. Let `GlobalExceptionHandler` handle validation errors

**Reference:** See SOLUTION_PLAN.md § 1.4

---

### ✅ Step 6: Update application-dev.properties
**File:** `Rentoza/src/main/resources/application-dev.properties`

**Status:** TODO  
**Estimated Time:** 10 min  
**Complexity:** Low

**Changes:**
```properties
# Add or update MySQL connection URL with maxAllowedPacket
spring.datasource.url=jdbc:mysql://localhost:3306/rentoza?\
  useUnicode=true&\
  characterEncoding=utf8mb4&\
  serverTimezone=UTC&\
  allowPublicKeyRetrieval=true&\
  useSSL=false&\
  zeroDateTimeBehavior=CONVERT_TO_NULL&\
  maxAllowedPacket=16M

# Update multipart settings if not already present
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=16MB
spring.servlet.multipart.enabled=true
```

**Reference:** See SOLUTION_PLAN.md § 1.5

---

### ✅ Step 7: Update application-prod.properties
**File:** `Rentoza/src/main/resources/application-prod.properties`

**Status:** TODO  
**Estimated Time:** 10 min  
**Complexity:** Low

**Changes:** Same as application-dev.properties (copy-paste the MySQL/multipart settings)

---

### ✅ Step 8: Create Unit Tests
**File:** `Rentoza/src/test/java/org/example/rentoza/car/CarRequestDTOValidationTest.java` (NEW)

**Status:** TODO  
**Estimated Time:** 1.5 hours  
**Complexity:** Low-Medium

**What to Create:**
1. Test validators reject images > 500 KB
2. Test validators reject > 10 images
3. Test validators reject invalid formats
4. Test validators accept valid payloads
5. Test individual image validation

**Reference:** See SOLUTION_PLAN.md § Testing Strategy

---

### ✅ Step 9: Create Integration Tests
**File:** `Rentoza/src/test/java/org/example/rentoza/car/CarControllerIntegrationTest.java` (NEW)

**Status:** TODO  
**Estimated Time:** 2 hours  
**Complexity:** Medium

**What to Create:**
1. Test car add endpoint rejects oversized images
2. Test car add endpoint accepts valid 5-image payload
3. Test GlobalExceptionHandler returns proper error responses
4. Test database is NOT modified on validation failure (no truncation)
5. Test regression: 5 MB payload no longer causes HTTP 400 with truncation

**Reference:** See SOLUTION_PLAN.md § Testing Strategy

---

## Frontend Changes (Est. 3-4 hours)

### ✅ Step 10: Update DocumentUploadComponent
**File:** `rentoza-frontend/src/app/features/owner/components/document-upload/document-upload.component.ts`

**Status:** TODO  
**Estimated Time:** 1 hour  
**Complexity:** Low

**Changes:**
1. Add `maxImageSize = 500 * 1024` constant (500 KB for images)
2. Separate max sizes for PDFs (10 MB) vs images (500 KB)
3. Check file size BEFORE base64 encoding
4. Check estimated size AFTER base64 encoding (factor in ~1.33x overhead)
5. Update error messages in Serbian

**Reference:** See SOLUTION_PLAN.md § 1.6

---

### ✅ Step 11: Update AddCarWizardComponent
**File:** `rentoza-frontend/src/app/features/owner/pages/add-car-wizard/add-car-wizard.component.ts`

**Status:** TODO  
**Estimated Time:** 1 hour  
**Complexity:** Low

**Changes:**
1. Update `handleFileSelect()` to enforce 500 KB limit per image
2. Check both raw file size AND estimated base64 size
3. Show Serbian error messages with guidance
4. Add `formatFileSize()` helper method
5. Update `submitForm()` error handling with specific guidance

**Reference:** See SOLUTION_PLAN.md § 1.6-1.7

---

### ✅ Step 12: Improve Error Messages
**File:** Same as Step 11

**Status:** TODO  
**Estimated Time:** 30 min  
**Complexity:** Low

**Changes:**
1. Extract error messages to translateable format
2. Provide Serbian guidance (e.g., "Kompresuj sliku...")
3. Show actual vs. max file size in error messages
4. Suggest JPEG over PNG for smaller file size

**Reference:** See SOLUTION_PLAN.md § 1.7

---

## DevOps / Database Changes (Est. 1-2 hours)

### ✅ Step 13: Update MySQL Configuration
**File:** `/etc/mysql/mysql.conf.d/mysqld.cnf` (or `/etc/my.cnf`)

**Status:** TODO  
**Estimated Time:** 30 min (includes service restart)  
**Complexity:** Low

**Changes:**
1. Locate `[mysqld]` section
2. Add or update: `max_allowed_packet = 16M`
3. Save file
4. Restart MySQL service: `sudo systemctl restart mysql`
5. Verify: `mysql -u root -p -e "SELECT @@max_allowed_packet;"`

**Expected Output:**
```
+-------------------+
| @@max_allowed_packet |
+-------------------+
| 16777216        |  (16 MB in bytes)
+-------------------+
```

**Reference:** See SOLUTION_PLAN.md § 1.5

---

### ✅ Step 14: Verify Database Configuration
**Command:** SSH to MySQL server

**Status:** TODO  
**Estimated Time:** 5 min  
**Complexity:** Low

```bash
# Check current setting
mysql -u root -p -e "SELECT @@max_allowed_packet;"

# Expected: 16777216 (16 MB)

# Check in all environments (dev, staging, prod)
# Repeat for each server
```

---

## Testing & Validation (Est. 4-6 hours)

### ✅ Step 15: Unit Test Execution
**Command:** Backend test runner

**Status:** TODO  
**Estimated Time:** 1 hour  
**Complexity:** Low

```bash
cd Rentoza
mvn clean test -Dtest=CarRequestDTOValidationTest
mvn clean test -Dtest=CarControllerIntegrationTest

# Expected: All tests pass
```

**Success Criteria:**
- ✅ Test coverage ≥ 80% for CarRequestDTO
- ✅ Test coverage ≥ 80% for CarController validation path
- ✅ Zero test failures

---

### ✅ Step 16: Integration Test Execution
**Command:** Same as Step 15

**Status:** TODO  
**Estimated Time:** 1.5 hours  
**Complexity:** Low

```bash
cd Rentoza
mvn clean test -Dtest=*Integration*

# Expected: All tests pass, including regression test
```

**Success Criteria:**
- ✅ Regression test passes: old bug cannot recur
- ✅ Error responses are formatted correctly
- ✅ Database state is unchanged after validation failures

---

### ✅ Step 17: Manual Testing (Dev Environment)

**Environment:** Local dev with mock data  
**Status:** TODO  
**Estimated Time:** 2-3 hours  
**Complexity:** Medium

#### Test Case 1: Valid Car with 5 Images
```
1. Open Add Car Wizard
2. Fill all required fields
3. Select 5 images (~500 KB each)
4. Click Submit
5. Expected: ✅ Car created, success message, redirect to /owner/cars
```

#### Test Case 2: Oversized Single Image
```
1. Open Add Car Wizard
2. Select image > 500 KB
3. Expected: ❌ Client-side error message (Serbian) before submission
4. Expected: Image not uploaded
```

#### Test Case 3: Too Many Images
```
1. Open Add Car Wizard
2. Try to upload 11 images
3. Expected: ❌ Error message "Maximum 10 images allowed"
4. Expected: Only first 10 accepted
```

#### Test Case 4: Invalid Format
```
1. Open Add Car Wizard
2. Try to upload .txt or .doc file
3. Expected: ❌ Error message "Dozvoljeni formati: PDF, JPEG, PNG"
```

#### Test Case 5: Bypass Frontend with cURL
```bash
# Create oversized image payload
curl -X POST http://localhost:8080/api/cars/add \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "brand": "BMW",
    "model": "320i",
    ...
    "imageUrl": "data:image/jpeg;base64,'$(head -c 1000000 /dev/urandom | base64)'",
    ...
  }'

# Expected: ❌ HTTP 400 with clear error message (NOT truncation error)
```

---

### ✅ Step 18: Staging Environment Testing

**Environment:** Production-like (with real DB)  
**Status:** TODO  
**Estimated Time:** 2-3 hours  
**Complexity:** Low

#### Smoke Tests
```
✓ Car creation with 1 image works
✓ Car creation with 5 images works
✓ Car creation with 10 images works
✓ Oversized image rejected with clear message
✓ Error messages appear in browser (no console errors)
✓ Database logs show ZERO truncation errors
```

#### Performance Tests
```
Measure:
- Request size: Should average 1-2 MB (down from potential 10+)
- Response time: Should be < 500ms
- CPU usage: Should be normal
```

#### Log Review
```bash
# Check for validation errors
grep "IMAGE_VALIDATION" /var/log/rentoza/app.log

# Check for database errors
grep "data_truncation\|Data too long" /var/log/mysql/error.log

# Expected: Minimal validation errors, zero database truncation
```

---

## Pre-Production Deployment Checklist

### ✅ Step 19: Code Review
**Owner:** Tech Lead  
**Status:** TODO  
**Estimated Time:** 1-2 hours

**Checklist:**
- [ ] DTO validators follow Spring validation best practices
- [ ] GlobalExceptionHandler covers all error cases
- [ ] Error messages are user-friendly (Serbian)
- [ ] Logging is structured and useful
- [ ] No security vulnerabilities introduced
- [ ] No hardcoded values/secrets
- [ ] Test coverage is adequate (>80%)
- [ ] Documentation is complete (comments in code)

---

### ✅ Step 20: Security Review
**Owner:** Security Team  
**Status:** TODO  
**Estimated Time:** 30 min

**Checklist:**
- [ ] Base64 string is validated (no injection attacks)
- [ ] File size validation prevents DoS attacks
- [ ] Error messages don't leak sensitive info
- [ ] Database errors are properly caught (no stack traces)
- [ ] Image validation doesn't allow other file types (via byte sniffing)

---

### ✅ Step 21: Database Backup
**Command:** System Administrator  
**Status:** TODO  
**Estimated Time:** 15 min

```bash
# Before deploying to production
mysqldump -u root -p rentoza > /backup/rentoza_$(date +%Y%m%d_%H%M%S).sql

# Verify backup
ls -lh /backup/rentoza_*.sql
```

---

### ✅ Step 22: Deployment to Staging
**Owner:** DevOps  
**Status:** TODO  
**Estimated Time:** 30 min

```bash
# 1. Build backend
cd Rentoza
mvn clean package -DskipTests

# 2. Deploy to staging
scp target/rentoza-0.0.1.jar staging:/opt/rentoza/

# 3. Restart service
ssh staging "systemctl restart rentoza"

# 4. Health check
curl http://staging:8080/actuator/health
```

---

### ✅ Step 23: Deployment to Production
**Owner:** DevOps  
**Status:** TODO  
**Estimated Time:** 30 min

**Pre-Deployment:**
- [ ] All staging tests pass
- [ ] Smoke tests completed
- [ ] Database backup taken
- [ ] Rollback plan ready

**Deployment:**
```bash
# 1. Build backend
cd Rentoza
mvn clean package -DskipTests

# 2. Deploy to production
scp target/rentoza-0.0.1.jar prod:/opt/rentoza/

# 3. Restart service with graceful shutdown
ssh prod "systemctl restart rentoza"

# 4. Health check
curl https://api.rentoza.com/actuator/health

# 5. Monitor logs
tail -f /var/log/rentoza/app.log
```

**Post-Deployment:**
- [ ] Monitor error rates
- [ ] Check car creation success rate
- [ ] Review database error logs
- [ ] Monitor image validation metrics

---

## Post-Deployment Monitoring (1st Week)

### ✅ Step 24: Set Up Alerts

**Metrics to Monitor:**
```
image.validation.failures > 10% per day  → Alert (too strict)
car.creation.success < 90%               → Alert (something broke)
http.requests.400 /api/cars/add > 5 per min → Investigate
database.errors.data_truncation > 0      → Alert (should be zero)
```

**Commands:**
```bash
# Enable metrics export
# Update application.properties:
management.endpoints.web.exposure.include=health,metrics,prometheus

# Scrape with Prometheus
# curl http://api.rentoza.com/actuator/prometheus

# Create Grafana dashboard for:
# - Image upload success rate
# - Car creation success rate
# - Database truncation errors (should be 0)
```

---

### ✅ Step 25: Monitor Logs Daily

**Commands:**
```bash
# Check for validation errors
grep -c "IMAGE_VALIDATION" /var/log/rentoza/app.log

# Check for database truncation
grep -c "data truncation\|Data too long" /var/log/mysql/error.log

# Expected Day 1: Few errors (expected as users test)
# Expected Day 7: Stable, < 5% validation errors
```

---

### ✅ Step 26: User Feedback

**Gather feedback:**
- [ ] Monitor support tickets for image upload complaints
- [ ] Check error logging for patterns
- [ ] Survey users: "Did Phase 1 fix your car listing issue?"

**Expected:**
- ✅ Most users can now complete car listings
- ✅ Validation error messages are clear
- ✅ No more random "Data truncation" 400 errors

---

## Rollback Procedure (If Issues Occur)

### ⚠️ Step 27: Emergency Rollback

**Duration:** < 15 minutes  
**Downtime:** ~2 minutes

```bash
# 1. Revert backend code to previous version
git checkout HEAD~1 -- Rentoza/src/main/

# 2. Rebuild and redeploy
cd Rentoza
mvn clean package -DskipTests
scp target/rentoza-0.0.1.jar prod:/opt/rentoza/
ssh prod "systemctl restart rentoza"

# 3. Verify
curl https://api.rentoza.com/actuator/health

# 4. Leave MySQL max_allowed_packet=16M (safe, helps)
```

**Note:** Frontend is NOT rolled back (image size checks don't hurt even with old backend)

---

## Success Criteria (Final Checklist)

### ✅ All of the Following Must Be True:

**Functional:**
- [ ] Users can upload cars with 5-10 images (~2-5 MB each)
- [ ] Frontend prevents oversized images before submission
- [ ] Backend validation rejects any oversized images that get through
- [ ] Error messages guide users ("Kompresuj sliku...")
- [ ] Car listing is saved successfully
- [ ] No HTTP 400 "Data truncation" errors

**Technical:**
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Regression test passes (old bug doesn't recur)
- [ ] Code coverage ≥ 80%
- [ ] Database logs show ZERO truncation errors
- [ ] MySQL `max_allowed_packet=16M` confirmed in all environments

**Operational:**
- [ ] Metrics dashboard shows:
  - Car creation success rate ≥ 95%
  - Image validation failures < 5%
  - Database truncation errors = 0
- [ ] No increase in error rate post-deployment
- [ ] Support team confirms reduced image-related complaints
- [ ] Performance metrics unchanged

---

## Summary

| Phase | Task | Owner | Status | Time |
|-------|------|-------|--------|------|
| 1 | Validators | Backend | TODO | 30m |
| 1 | GlobalExceptionHandler | Backend | TODO | 1.5h |
| 1 | CarService validation | Backend | TODO | 1h |
| 1 | CarController update | Backend | TODO | 30m |
| 1 | DB config (dev) | Backend | TODO | 10m |
| 1 | DB config (prod) | DevOps | TODO | 10m |
| 1 | Frontend image checks | Frontend | TODO | 1h |
| 1 | Frontend error handling | Frontend | TODO | 30m |
| 1 | Unit tests | QA | TODO | 1.5h |
| 1 | Integration tests | QA | TODO | 2h |
| 1 | Staging testing | QA | TODO | 2-3h |
| 1 | Production deployment | DevOps | TODO | 30m |
| 1 | Monitoring setup | DevOps | TODO | 30m |
| **TOTAL** | | | | **13-16h** |

---

**Document Version:** 1.0  
**Status:** Ready for Implementation  
**Last Updated:** 2025-12-14

See **ROOT_CAUSE_ANALYSIS.md** for diagnosis.  
See **SOLUTION_PLAN.md** for complete code examples.  
See **INCIDENT_SUMMARY.md** for quick reference.
