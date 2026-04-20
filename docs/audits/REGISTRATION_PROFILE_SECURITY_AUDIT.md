# Security Audit: Registration & User Profile Feature Module

> **Auditor**: GitHub Copilot (Claude Opus 4.6)
> **Scope**: Registration, authentication, user profile management, profile picture handling, identity verification
> **Date**: 2025-07-17 (revised 2026-03-05)
> **Codebase**: Rentoza — Peer-to-peer car rental marketplace (Serbian market)
> **Backend**: Spring Boot (Java) + PostgreSQL + Supabase Auth
> **Frontend**: Angular (TypeScript) + Angular Material

---

## Risk Dashboard

| Severity | Count | Status |
|----------|-------|--------|
| 🔴 CRITICAL | 1 | Requires immediate remediation |
| 🟠 HIGH | 7 | Schedule for next sprint |
| 🟡 MEDIUM | 11 | Track in backlog |
| 🔵 LOW | 6 | Address opportunistically |
| **TOTAL** | **25** | — |

---

## Assumptions

| # | Assumption | Confidence | Impact if Wrong |
|---|-----------|------------|-----------------|
| A1 | `legacy.auth.enabled=false` in production (deprecated `AuthController` is inactive) | HIGH — guarded by `@ConditionalOnProperty(matchIfMissing = false)` | CRITICAL — exposes legacy HS256 JWT + password login endpoints |
| A2 | `registration.enhanced=true` in production — `EnhancedAuthController` is **active** | VERIFIED — `application-prod.properties` line 15 | H-1 is an active exposure, not conditional |
| A3 | `PII_ENCRYPTION_KEY` environment variable is at least 16 bytes | MEDIUM — no prod config reviewed | CRITICAL — `AttributeEncryptor` truncates to 16 bytes silently |
| A4 | Supabase Storage bucket `user-avatars` has appropriate ACL policies (not world-writable) | LOW — bucket config not auditable from codebase | HIGH — arbitrary file upload to storage |
| A5 | Frontend renders Bio field with Angular's default HTML sanitization (no `[innerHTML]` binding) | HIGH — standard Angular behavior | HIGH — stored XSS if bypassed |
| A6 | The chat-service `SupabaseJwtAuthFilter` `@Component` auto-registration does not conflict with explicit filter chain ordering | MEDIUM — different registration strategy than backend | MEDIUM — could bypass auth filter ordering |

---

## Security Findings

### 🔴 CRITICAL

---

#### C-1: SHA-256 Hashing of Identity Documents Without Salt Enables Brute-Force Recovery

**Dimension**: 2 — Security & Authorization (STRIDE: Information Disclosure)
**Confidence**: VERIFIED
**Files**: [HashUtil.java](apps/backend/src/main/java/org/example/rentoza/util/HashUtil.java), [ProfileCompletionService.java](apps/backend/src/main/java/org/example/rentoza/user/ProfileCompletionService.java), [OwnerVerificationService.java](apps/backend/src/main/java/org/example/rentoza/user/OwnerVerificationService.java)

**Description**: `HashUtil.hash()` uses unsalted SHA-256 to generate hashes for JMBG (13-digit Serbian national ID) and PIB (9-digit Serbian tax ID). These hashes are stored in the database alongside encrypted values for uniqueness checks.

```java
// HashUtil.java — No salt, no iterations, no pepper
public String hash(String input) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(hash);
}
```

**Attack Surface**:
- **JMBG**: 13-digit number → 10^13 possible values. A GPU can compute ~10 billion SHA-256 hashes/second. Full brute-force: **~17 minutes** on a single modern GPU.
- **PIB**: 9-digit number → 10^9 possible values. Full brute-force: **< 1 second**.
- **Driver license numbers**: Similar low-entropy, same vulnerability.

An attacker with read access to the database (SQL injection, backup leak, insider threat) can recover every owner's national ID number by brute-forcing the hash column.

**Impact**: Complete PII disclosure of all owner identity documents. Violates GDPR Article 32 (appropriate technical measures). In Serbia, JMBG is effectively a Social Security Number — its exposure enables identity theft, financial fraud, and regulatory penalties under ZZPL (Serbian Data Protection Law).

**Remediation**:
```java
// Use HMAC-SHA256 with a server-side secret (pepper) + per-record salt
// Or use a slow hash like bcrypt/argon2 for uniqueness checks
public String hash(String input) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(pepperKey, "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(input.getBytes(UTF_8)));
}
```
At minimum, use HMAC-SHA256 with a secret pepper stored outside the database. This makes brute-force infeasible without the pepper.

---

### 🟠 HIGH

---

#### H-1: Email Enumeration Inconsistency Between Registration Controllers

**Dimension**: 2 — Security & Authorization (STRIDE: Information Disclosure)
**Confidence**: VERIFIED
**Files**: [SupabaseAuthController.java](apps/backend/src/main/java/org/example/rentoza/auth/SupabaseAuthController.java#L514), [EnhancedAuthController.java](apps/backend/src/main/java/org/example/rentoza/auth/EnhancedAuthController.java#L121)

**Description**: `SupabaseAuthController.register()` correctly returns HTTP 200 for duplicate emails to prevent enumeration. However, `EnhancedAuthController.registerUser()` returns HTTP 409 with `"EMAIL_ALREADY_REGISTERED"`, and `registerOwner()` does the same. Phone enumeration follows the same pattern.

- `POST /api/auth/supabase/register` → 200 (anti-enumeration ✅)
- `POST /api/auth/register/user` → 409 (enumeration leak ❌)
- `POST /api/auth/register/owner` → 409 (enumeration leak ❌)

**Impact**: `registration.enhanced=true` is confirmed active in production (`application-prod.properties` line 15). This is an **active exposure**: attackers can probe email/phone existence via `POST /api/auth/register/user` (409 response) while `POST /api/auth/supabase/register` correctly returns 200. The anti-enumeration protection on the primary endpoint is directly undermined by the Enhanced registration endpoints being live.

**Remediation**: Apply the same 200-response pattern to `EnhancedAuthController`, or remove the controller if it's deprecated. Document the anti-enumeration pattern as a security requirement.

---

#### H-2: UserController.getMyProfile() Leaks Exception Messages to Client

**Dimension**: 2 — Security & Authorization (STRIDE: Information Disclosure)
**Confidence**: VERIFIED
**File**: [UserController.java](apps/backend/src/main/java/org/example/rentoza/user/UserController.java#L78-L82)

**Description**: The `/api/users/me` endpoint catches all `RuntimeException` and returns `e.getMessage()` directly in the response body:

```java
catch (RuntimeException e) {
    return ResponseEntity.status(401).body(Map.of(
            "error", e.getMessage(),
            "authenticated", false
    ));
}
```

This pattern repeats in `getProfileSummary()`, `getProfileDetails()`, and `updateMyProfile()`.

**Impact**:
- Database exceptions leak table names, column names, SQL fragments
- `NullPointerException` messages leak internal class/field names
- All non-auth errors are misclassified as 401, making debugging and monitoring unreliable
- The `GlobalExceptionHandler` (which properly sanitizes errors) is bypassed by this catch block

**Remediation**: Remove the try-catch blocks and let `GlobalExceptionHandler` handle exceptions consistently. If manual handling is needed, return a generic message and log the exception details server-side.

---

#### H-3: Bio Field Not Sanitized in updateProfileSecure() — Stored XSS Risk

**Dimension**: 2 — Security & Authorization (STRIDE: Tampering)
**Confidence**: VERIFIED
**Files**: [UserService.java](apps/backend/src/main/java/org/example/rentoza/user/UserService.java#L177-L209), [InputSanitizer.java](apps/backend/src/main/java/org/example/rentoza/security/InputSanitizer.java)

**Description**: `InputSanitizer` exists and is used for name fields during registration, but `UserService.updateProfileSecure()` does NOT call it for the `bio` field. The bio is only trimmed and length-checked:

```java
if (dto.getBio() != null) {
    String bio = dto.getBio().trim();
    if (bio.length() > 300) {
        throw new ValidationException("Bio must be maximum 300 characters");
    }
    user.setBio(bio.isBlank() ? null : bio);
}
```

A user can submit `<script>alert('xss')</script>` or `<img src=x onerror=alert(1)>` as their bio.

**Impact**: If any part of the application renders the bio with `[innerHTML]` or in an admin dashboard without escaping, this becomes a stored XSS vulnerability affecting all users who view the profile. The bio is likely rendered in the public profile view, search results, and booking counterparty views.

**Remediation**: Call `InputSanitizer.sanitizeText(bio)` (add this method if it doesn't exist) or use a library like OWASP Java HTML Sanitizer. At minimum, strip HTML tags and XSS patterns using the existing `InputSanitizer` patterns.

---

#### H-4: No Endpoint-Specific Rate Limit on Profile Picture Uploads

**Dimension**: 5 — Performance & Scalability
**Confidence**: VERIFIED
**Files**: [ProfilePictureController.java](apps/backend/src/main/java/org/example/rentoza/user/ProfilePictureController.java), [ProfilePictureService.java](apps/backend/src/main/java/org/example/rentoza/user/ProfilePictureService.java), RateLimitingFilter configuration

**Description**: The profile picture upload endpoint `POST /api/users/me/profile-picture` has no endpoint-specific rate limit. It falls through to the default rate limit of 100 requests per 60 seconds. Each upload can be up to 4MB and triggers:
- MIME validation
- Magic byte validation
- Image decoding (decompression bomb check)
- EXIF stripping
- Resize to 512x512
- JPEG compression
- Supabase Storage upload (external API call)

**Impact**: An authenticated user could trigger 100 image processing operations per minute, each consuming CPU (image resize), memory (image decoding), and external API quota (Supabase Storage). This is a **resource exhaustion** vector. At 4MB × 100 = 400MB/minute of inbound traffic plus CPU-intensive image processing.

**Remediation**: Add endpoint-specific rate limit of 5-10 uploads per minute:
```properties
rate.limiting.endpoints[N].pattern=/api/users/me/profile-picture
rate.limiting.endpoints[N].method=POST
rate.limiting.endpoints[N].maxRequests=5
rate.limiting.endpoints[N].windowSeconds=60
```

---

#### H-5: UserController.getMyProfile() Returns Raw HashMap Instead of Typed DTO

**Dimension**: 8 — API Contract & Frontend Integration
**Confidence**: VERIFIED
**File**: [UserController.java](apps/backend/src/main/java/org/example/rentoza/user/UserController.java#L61-L76)

**Description**: The `/api/users/me` endpoint constructs its response using a raw `HashMap<String, Object>`:

```java
java.util.Map<String, Object> response = new java.util.HashMap<>();
response.put("id", user.getId());
response.put("email", user.getEmail());
response.put("firstName", user.getFirstName());
// ... 10+ more fields
```

Meanwhile, other endpoints use typed DTOs (`UserResponseDTO`, `ProfileDetailsDTO`).

**Impact**:
- No compile-time guarantee of which fields are exposed — a developer could accidentally add `response.put("jmbg", user.getJmbg())` and it would compile without review flags
- No Jackson annotation control (`@JsonIgnore`, `@JsonProperty`) for serialization behavior
- API contract can silently drift — fields can be added/removed without breaking compilation
- Inconsistent with the rest of the API surface

**Remediation**: Replace with `UserResponseDTO` or create a dedicated `UserMeResponseDTO` that explicitly declares the response shape. Apply `@JsonInclude(NON_NULL)` for optional fields.

---

#### H-6: Defense-in-Depth Gap — Inconsistent @PreAuthorize Annotations on UserController

**Dimension**: 4 — Architecture & Design Patterns
**Confidence**: VERIFIED
**File**: [UserController.java](apps/backend/src/main/java/org/example/rentoza/user/UserController.java)

**Description**: Inconsistent use of method-level security annotations across UserController:

| Endpoint | @PreAuthorize | Protection Source |
|----------|--------------|-------------------|
| `GET /api/users/me` | `isAuthenticated()` ✅ | Annotation + SecurityConfig |
| `PATCH /api/users/me` | None ❌ | SecurityConfig only |
| `GET /api/users/profile` | None ❌ | SecurityConfig only |
| `GET /api/users/profile/details` | None ❌ | SecurityConfig `.anyRequest().authenticated()` fallback |
| `POST /api/users/complete-profile` | `isAuthenticated()` ✅ | Annotation + SecurityConfig |
| `GET /api/users/profile-completion-status` | `isAuthenticated()` ✅ | Annotation + SecurityConfig |
| `GET /api/users/profile/{userId}` | None ❌ | SecurityConfig `INTERNAL_SERVICE` |

**Impact**: If the SecurityConfig URL patterns are refactored (e.g., changing from `/api/users/**` to `/api/v2/users/**`), endpoints without `@PreAuthorize` could become publicly accessible. The inconsistency also makes security audits harder — you can't determine protection level from the controller alone.

**Remediation**: Add `@PreAuthorize("isAuthenticated()")` to all authenticated endpoints. For `getUserProfileById()`, add `@PreAuthorize("hasAuthority('INTERNAL_SERVICE')")`.

---

#### H-7: AttributeEncryptor Legacy ECB Fallback Returns Raw Data on Decryption Failure

**Dimension**: 3 — Data Integrity & Validation
**Confidence**: VERIFIED
**File**: [AttributeEncryptor.java](apps/backend/src/main/java/org/example/rentoza/util/AttributeEncryptor.java)

**Description**: When `decryptLegacyEcb()` fails, it returns the raw database value as plaintext. This is an **intentional migration mechanism** — inline comments document a three-tier decryption strategy (AES-GCM → AES-ECB → plaintext passthrough) designed to handle pre-encryption rows from initial deployment. On the next entity `save()`, plaintext values are re-encrypted with AES-GCM.

```java
private String decryptLegacyEcb(String dbData) {
    try {
        Cipher cipher = Cipher.getInstance(AES_ECB);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return new String(cipher.doFinal(Base64.getDecoder().decode(dbData)));
    } catch (Exception e) {
        // Final fallback: data may be unencrypted plaintext from before encryption was enabled.
        // Return as-is so the entity can be loaded and re-saved with GCM encryption.
        log.warn("[AttributeEncryptor] ECB decryption failed, treating as legacy plaintext.");
        return dbData;
    }
}
```

**Why HIGH, not CRITICAL**: The fallback serves a documented, legitimate migration purpose. However, there is no monitoring to detect:
- Whether all plaintext rows have been re-encrypted (migration completeness)
- Whether fallback triggers are from legitimate migration vs data corruption / key mismatch
- Whether an attacker has injected crafted non-`GCM$` values into encrypted columns

**Impact**: Without alerting on fallback triggers, a data corruption or key rotation event could silently expose PII in API responses. The migration convenience trades safety for liveability — acceptable during initial rollout, but should be hardened once migration is confirmed complete.

**Remediation**:
1. Add a counter/metric on fallback triggers — alert if any occur after migration cutoff date
2. Run a migration audit query to confirm zero plaintext rows remain
3. Once confirmed, replace `return dbData` with `throw new IllegalStateException()` and remove the ECB fallback path entirely

---

### 🟡 MEDIUM

---

#### M-1: PasswordPolicyService Creates Its Own BCryptPasswordEncoder

**Dimension**: 4 — Architecture & Design Patterns
**Confidence**: VERIFIED
**File**: [PasswordPolicyService.java](apps/backend/src/main/java/org/example/rentoza/security/password/PasswordPolicyService.java)

**Description**: The constructor creates a standalone `BCryptPasswordEncoder` instead of injecting the Spring-managed bean:

```java
public PasswordPolicyService(PasswordHistoryRepository passwordHistoryRepository) {
    this.passwordHistoryRepository = passwordHistoryRepository;
    this.passwordEncoder = new BCryptPasswordEncoder(); // Not injected
}
```

The same pattern exists in `SupabaseAuthController`. This creates at least 3 BCrypt instances across the codebase (Spring bean + PasswordPolicyService + SupabaseAuthController), each potentially with different cost factors.

**Remediation**: Inject `PasswordEncoder` from Spring context via constructor parameter.

---

#### M-2: No Maximum Age Validation on Registration

**Dimension**: 1 — Business Logic
**Confidence**: VERIFIED
**Files**: [ProfileCompletionService.java](apps/backend/src/main/java/org/example/rentoza/user/ProfileCompletionService.java), [SupabaseAuthController.java](apps/backend/src/main/java/org/example/rentoza/auth/SupabaseAuthController.java)

**Description**: Registration validates minimum age (21+) but has no maximum age check. A date of birth of `1800-01-01` would be accepted. While the `@Past` annotation ensures DOB is in the past, there's no upper reasonableness bound.

**Impact**: Data quality issue. Unrealistic ages could affect analytics, insurance calculations, and business logic downstream. Most car rental platforms cap at 99 or 120 years.

**Remediation**: Add `@PastOrPresent` with a custom validator: `LocalDate.now().minusYears(120)` as minimum birth date.

---

#### M-3: OwnerVerificationService.rejectIdentityVerification() Clears Both JMBG and PIB

**Dimension**: 1 — Business Logic
**Confidence**: VERIFIED
**File**: [OwnerVerificationService.java](apps/backend/src/main/java/org/example/rentoza/user/OwnerVerificationService.java)

**Description**: When identity verification is rejected, `rejectIdentityVerification()` clears **both** JMBG and PIB fields regardless of owner type:

```java
user.setJmbg(null);
user.setJmbgHash(null);
user.setPib(null);
user.setPibHash(null);
```

An INDIVIDUAL owner (who submitted JMBG) would also have their PIB cleared (if they had one from a previous attempt), and vice versa. The `rejectionReason` parameter is logged but **not stored** on the User entity.

**Impact**: Rejection reason is lost for audit trail. The blanket clearing may have unintended side effects if a user transitions between owner types.

**Remediation**: Store `rejectionReason` on the entity. Only clear fields relevant to the current `ownerType`.

---

#### M-4: IBAN Validation Not Applied in ProfileCompletionService

**Dimension**: 3 — Data Integrity & Validation
**Confidence**: VERIFIED
**Files**: [ProfileCompletionService.java](apps/backend/src/main/java/org/example/rentoza/user/ProfileCompletionService.java), [IdentityDocumentValidator.java](apps/backend/src/main/java/org/example/rentoza/user/validation/IdentityDocumentValidator.java)

**Description**: `ProfileCompletionService` validates bank account format with a simple regex `^RS[0-9]{22}$`, but `IdentityDocumentValidator` has a proper IBAN checksum validator (`isValidIbanChecksum()` using MOD-97). The checksum validation is **not called** during profile completion.

Similarly, `OwnerVerificationService` doesn't validate IBAN format at all — the `bankAccountNumber` parameter is stored without validation.

**Impact**: Invalid IBAN numbers can be stored, causing payment failures when owners try to receive payouts.

**Remediation**: Call `IdentityDocumentValidator.validateIban()` in both `ProfileCompletionService` and `OwnerVerificationService`.

---

#### M-5: Duplicate JMBG/PIB Validation Logic Across Services

**Dimension**: 4 — Architecture & Design Patterns
**Confidence**: VERIFIED
**Files**: [OwnerVerificationService.java](apps/backend/src/main/java/org/example/rentoza/user/OwnerVerificationService.java), [ProfileCompletionService.java](apps/backend/src/main/java/org/example/rentoza/user/ProfileCompletionService.java), [IdentityDocumentValidator.java](apps/backend/src/main/java/org/example/rentoza/user/validation/IdentityDocumentValidator.java)

**Description**: JMBG and PIB validation is implemented in three different places:
1. `OwnerVerificationService.isValidJmbg()`/`isValidPib()` — full checksum validation
2. `IdentityDocumentValidator.validateJmbg()`/`validatePib()` — full checksum validation (slightly different algorithm for JMBG)
3. `ProfileCompletionService` — regex-only validation (no checksum)

Both `OwnerVerificationService` and `IdentityDocumentValidator` implement equivalent JMBG weighting logic, but having two separate implementations creates maintenance risk — a bugfix applied to one may not propagate to the other.

**Impact**: Three separate validation paths with inconsistent depth (checksum vs regex-only) reduces confidence in data integrity and increases the surface for divergence bugs.

**Remediation**: Consolidate all identity document validation into `IdentityDocumentValidator` and call it from all services. Remove duplicate implementations.

---

#### M-6: Phone Number Not Normalized — Multiple Formats for Same Number

**Dimension**: 3 — Data Integrity & Validation
**Confidence**: VERIFIED
**Files**: [UpdateProfileRequestDTO.java](apps/backend/src/main/java/org/example/rentoza/user/dto/UpdateProfileRequestDTO.java), [UserService.java](apps/backend/src/main/java/org/example/rentoza/user/UserService.java)

**Description**: Phone validation uses `^[0-9]{8,15}$` — digits only, no country code requirement. `updateProfileSecure()` strips non-digit characters but doesn't normalize to E.164 format. The same phone number could be stored as `0641234567`, `381641234567`, or `641234567`.

The uniqueness constraint on the `phone` column means the same physical phone could register multiple accounts with different format representations.

**Impact**: Duplicate accounts by format variation. SMS delivery failures. Inconsistent search/lookup.

**Remediation**: Normalize phone numbers to E.164 format (e.g., `+381641234567`) using a library like Google's libphonenumber. Validate that the country code is `+381` for the Serbian market.

---

#### M-7: avatarUrl Field Has No URL Validation in updateProfileSecure()

**Dimension**: 2 — Security & Authorization (STRIDE: Tampering)
**Confidence**: VERIFIED
**File**: [UserService.java](apps/backend/src/main/java/org/example/rentoza/user/UserService.java)

**Description**: The `avatarUrl` field in `updateProfileSecure()` is only length-checked (≤500 chars). No URL scheme validation is performed. A user could set their avatar URL to:
- `javascript:alert(1)` — XSS via protocol handler
- `data:text/html,<script>...` — data URI injection
- An arbitrary external URL pointing to offensive content
- An internal network URL (`http://169.254.169.254/...`) for SSRF if the URL is fetched server-side

**Impact**: If the avatar URL is rendered in an `<img>` or `<a>` tag, protocol injection is possible. If any backend service fetches the URL (e.g., for caching or thumbnail generation), SSRF is possible.

**Remediation**: Validate that `avatarUrl` starts with the expected Supabase Storage base URL. In `updateProfileSecure()`, restrict to Supabase host origins only. The profile picture upload flow already generates correct URLs — the raw `avatarUrl` field should either be removed from the update DTO or heavily restricted.

---

#### M-8: LastName Update Allows Change Only If GooglePlaceholder — Not Auditable

**Dimension**: 11 — Observability & Debugging
**Confidence**: VERIFIED
**File**: [UserService.java](apps/backend/src/main/java/org/example/rentoza/user/UserService.java)

**Description**: `updateProfileSecure()` allows `lastName` changes only if the current value is `"GooglePlaceholder"`:

```java
if (dto.getLastName() != null) {
    if (user.getLastName() != null && !user.getLastName().equals("GooglePlaceholder")) {
        throw new ValidationException("Last name cannot be changed once set");
    }
    // Updates lastName
}
```

No audit log is created when a lastName is set from placeholder to real value. If the placeholder detection fails (e.g., Google APIs change the default value), users could be permanently locked out of setting their last name.

**Impact**: No audit trail for name changes. Fragile string comparison for placeholder detection.

**Remediation**: Store a `nameSource` enum (MANUAL, GOOGLE_OAUTH, PLACEHOLDER) instead of comparing values. Log name changes for audit.

---

#### M-9: DOB Modification Blocked If "OCR Verified" But No Formal Dispute Process

**Dimension**: 1 — Business Logic
**Confidence**: VERIFIED
**File**: [UserService.java](apps/backend/src/main/java/org/example/rentoza/user/UserService.java)

**Description**: `updateProfileSecure()` blocks DOB changes if `user.getDobVerified()` is true (OCR verified from driver's license). There's no formal dispute/correction process documented. If OCR extracted an incorrect DOB, the user has no self-service path to correct it.

**Impact**: Users with OCR errors in DOB are permanently stuck. This affects age eligibility checks (21+ requirement) and could prevent legitimate users from renting.

**Remediation**: Implement a DOB correction request flow that goes through admin review. Log the OCR source for comparison.

---

#### M-10: Deprecated Driver License Fields Still Present in CompleteProfileRequestDTO

**Dimension**: 8 — API Contract & Frontend Integration
**Confidence**: VERIFIED
**File**: [CompleteProfileRequestDTO.java](apps/backend/src/main/java/org/example/rentoza/user/dto/CompleteProfileRequestDTO.java)

**Description**: Three driver license fields are marked `@Deprecated(forRemoval = true)` and `@JsonIgnore`, but still exist in the DTO:

```java
@Deprecated(since = "Phase 4 - License OCR Migration", forRemoval = true)
@JsonIgnore
private String driverLicenseNumber;
```

**Impact**: While `@JsonIgnore` prevents deserialization, the fields add confusion and increase the DTO's attack surface if `@JsonIgnore` is accidentally removed.

**Remediation**: Remove these fields in the next cleanup sprint. They are already ignored by Jackson.

---

#### M-11: Chat Service Has a Separate SupabaseJwtAuthFilter Implementation

**Dimension**: 4 — Architecture & Design Patterns
**Confidence**: VERIFIED
**Files**: Backend `SupabaseJwtAuthFilter.java`, Chat-service `SupabaseJwtAuthFilter.java`

**Description**: The chat-service has its own copy of `SupabaseJwtAuthFilter` registered as `@Component` (Spring auto-scan), while the backend registers its filter as a `@Bean` in SecurityConfig. The chat-service filter also supports dual auth: Supabase ES256 JWT + internal HS256 JWT via `X-Internal-Service-Token`.

**Impact**: Security patches to the backend's JWT filter must be manually replicated in the chat-service copy. Different registration strategies could cause filter ordering issues. The dual-auth in chat-service increases attack surface.

**Remediation**: Extract shared JWT validation into a common library module. Use the same filter registration strategy in both services.

---

### 🔵 LOW

---

#### L-1: Emoji Characters in Log Messages

**Dimension**: 11 — Observability & Debugging
**Confidence**: VERIFIED
**File**: [ProfilePictureController.java](apps/backend/src/main/java/org/example/rentoza/user/ProfilePictureController.java)

**Description**: Log messages use emoji characters (`📸`, `✅`, `⚠️`, `🔒`, `❌`, `🗑️`), which may cause issues with log aggregation tools, grep/awk parsing, and some terminal encoders.

**Remediation**: Use text-based log level prefixes. SLF4J log levels are sufficient for categorization.

---

#### L-2: OwnerRegistrationDTO Validates Password Pattern But May Diverge from PasswordPolicyService

**Dimension**: 3 — Data Integrity & Validation
**Confidence**: VERIFIED
**Files**: [OwnerRegistrationDTO.java](apps/backend/src/main/java/org/example/rentoza/user/dto/OwnerRegistrationDTO.java), [PasswordPolicyService.java](apps/backend/src/main/java/org/example/rentoza/security/password/PasswordPolicyService.java)

**Description**: `OwnerRegistrationDTO` has a `@Pattern` annotation requiring uppercase + lowercase + digit, but `PasswordPolicyService.validatePasswordStrength()` also requires a **special character**. The DTO validation is weaker than the service validation.

**Impact**: A password like `Password1` passes DTO validation but fails PasswordPolicyService. The user sees different error messages depending on which layer rejects first.

**Remediation**: Remove password pattern from DTOs and rely solely on `PasswordPolicyService` for consistent enforcement. Use a custom JSR-303 constraint validator backed by `PasswordPolicyService`.

---

#### L-3: Frontend DOB Validation Uses 18 Years While Backend Requires 21

**Dimension**: 8 — API Contract & Frontend Integration
**Confidence**: VERIFIED
**Files**: Frontend `profile.component.ts`, Backend `ProfileCompletionService.java`

**Description**: The frontend `profile.component.ts` calculates `maxDob` as today minus 18 years for the date picker. But the backend `ProfileCompletionService` enforces 21 years minimum age:

```java
// Backend — 21 years
if (Period.between(dateOfBirth, LocalDate.now()).getYears() < 21) {
    throw new ValidationException("Morate imati najmanje 21 godinu");
}
```

**Impact**: Users aged 18-20 can select a DOB on the frontend but will get a 400 error from the backend. Poor UX but not a security issue since the backend enforces the correct rule.

**Remediation**: Update frontend `maxDob` calculation to use 21 years.

---

#### L-4: UpdateProfileRequestDTO lastName Minimum Length Is 3 Characters

**Dimension**: 3 — Data Integrity & Validation
**Confidence**: VERIFIED
**File**: [UpdateProfileRequestDTO.java](apps/backend/src/main/java/org/example/rentoza/user/dto/UpdateProfileRequestDTO.java)

**Description**: `@Size(min = 3, max = 50)` on `lastName` rejects valid 2-character surnames (e.g., "Li", "Wu", "Ma" — common in East Asian names, and occasional Serbian surnames like "Na").

**Impact**: Some legitimate users cannot set their real last name. Edge case for the Serbian market but a data integrity issue.

**Remediation**: Lower minimum to 1 or 2 characters.

---

#### L-5: CurrentUser.verifyOwnership() Formats User IDs in Exception Messages

**Dimension**: 2 — Security & Authorization
**Confidence**: VERIFIED
**File**: [CurrentUser.java](apps/backend/src/main/java/org/example/rentoza/security/CurrentUser.java)

**Description**: `verifyOwnership()` includes user IDs in the `AccessDeniedException` message:
```java
"Unauthorized to access %s: user %d does not own entity owned by %d"
```

If this message reaches the client (via `GlobalExceptionHandler`), it leaks the owner's user ID.

**Remediation**: `GlobalExceptionHandler` does handle `AccessDeniedException` with a generic 403 message, so this is likely not exposed. But verify that the detailed message is only logged, not returned.

---

#### L-6: Optimistic Locking on User Entity — No Retry Strategy for Profile Updates

**Dimension**: 6 — Edge Cases & Error Handling
**Confidence**: VERIFIED
**File**: [User.java](apps/backend/src/main/java/org/example/rentoza/user/User.java) (has `@Version` field)

**Description**: The User entity has `@Version` for optimistic locking. If two concurrent requests update the same user (e.g., profile update + profile picture upload), one will fail with `OptimisticLockException`. `GlobalExceptionHandler` returns 409 STALE_DATA, but there's no retry mechanism.

**Impact**: Infrequent race condition. Frontend would need to retry the update. Since profile updates and picture uploads are separate flows, simultaneous conflicts would be rare.

**Remediation**: Consider adding `@Retryable` with backoff for profile update operations, or document the retry expectation for frontend consumers.

---

## Platform Gaps (vs. Industry Standard: Turo, Getaround, Airbnb)

| # | Feature | Industry Standard | Rentoza Status | Priority |
|---|---------|-------------------|---------------|----------|
| MF-1 | Account linking/unlinking (Google ↔ Password) | Turo, Airbnb: users can link multiple providers from profile settings | Error message references "link from profile settings" but no linking endpoint exists | HIGH |
| MF-2 | Multi-factor authentication (MFA/2FA) | Turo: SMS 2FA, Airbnb: TOTP + SMS | Not implemented. No MFA enrollment endpoints, no MFA challenge in login flow | HIGH |
| MF-3 | Login notification (new device/location alerts) | Standard in all major platforms | No device fingerprinting beyond basic login tracking. `lastFailedLoginIp` exists but no successful login IP/device tracking | MEDIUM |
| MF-4 | Account deletion self-service UI | GDPR Article 17 right to erasure. Turo: Settings → Delete Account | `GdprController.java` provides `DELETE /api/users/me/delete` with pre-condition checks, cancellation (`POST /cancel-deletion`), and data export (`GET /data-export`). Backend API complete. Frontend self-service UI not yet implemented | MEDIUM |
| MF-5 | Profile change history / audit log | Airbnb: profile change notifications | No audit trail for profile field changes (name, phone, bio). Only `@Version` optimistic locking exists | MEDIUM |
| MF-6 | Identity verification via government ID photo (KYC) | Turo: ID photo + selfie verification, Getaround: same | Driver license OCR migration in progress. No selfie liveness check. No government ID photo upload for JMBG/PIB verification | HIGH |
| MF-7 | Phone number verification via SMS OTP | Turo, Airbnb: mandatory phone verification | Phone stored but not verified. No OTP generation or verification endpoint | HIGH |
| MF-8 | Session management UI (view/revoke active sessions) | Standard in Google, Airbnb, Turo | No endpoint to list or revoke individual sessions. Logout invalidates current token only | LOW |
| MF-9 | Password breach check (HaveIBeenPwned integration) | NIST 800-63B recommendation. Airbnb implements | `PasswordPolicyService` checks reuse and strength but not against known breach databases | LOW |
| MF-10 | Email change with re-verification | Standard in all platforms | No email change endpoint. Email is a locked field on the frontend. Backend has no `updateEmail()` flow | MEDIUM |

---

## Positive Observations

| # | Observation | Files | Notes |
|---|------------|-------|-------|
| P-1 | **Anti-enumeration on primary registration** | `SupabaseAuthController.register()` | Returns 200 for duplicate emails — industry best practice. Well-documented with inline comments |
| P-2 | **PII encryption with AES-GCM** | `AttributeEncryptor.java` | Modern authenticated encryption with random IVs for JMBG, PIB, bank account, driver license. Migration path from legacy ECB |
| P-3 | **Account lockout with progressive backoff** | `SupabaseAuthController.login()` | 5 failed attempts → lockout. Prevents brute-force. Combined with rate limiting |
| P-4 | **Google OAuth account takeover prevention** | `SupabaseAuthService.syncGoogleUserToLocalDatabase()` | Refuses to auto-link LOCAL password accounts by email. Requires verified email. Validates Google provider identity |
| P-5 | **CSRF protection with selective exemptions** | `SecurityConfig.java` | Enabled globally with strategic exemptions for APIs that have alternative auth (webhooks, OAuth callbacks) |
| P-6 | **Comprehensive security headers** | `SecurityConfig.java` | CSP, HSTS (1 year), X-Content-Type-Options: nosniff, Referrer-Policy: same-origin, X-Frame-Options: sameOrigin |
| P-7 | **Profile picture security** | `ProfilePictureService.java` | MIME whitelist + magic byte validation + EXIF stripping + decompression bomb protection + resize normalization — industry-leading image security |
| P-8 | **Password reuse prevention** | `PasswordPolicyService.java` | Checks last 3 passwords via BCrypt comparison. Seeded on registration |
| P-9 | **JMBG/PIB checksum validation** | `OwnerVerificationService.java` | Not just format validation — actual Serbian national ID checksum algorithms implemented correctly |
| P-10 | **Stateless session management** | `SecurityConfig.java` | `SessionCreationPolicy.STATELESS` — no server-side session state, proper JWT-based auth |
| P-11 | **Token denylist on logout** | `SupabaseJwtAuthFilter.java` | Tokens are denylisted on logout, preventing reuse of logged-out tokens |
| P-12 | **Secure cookie configuration** | `SupabaseAuthController.java` | HttpOnly, Secure (configurable), SameSite, path-scoped refresh tokens |
| P-13 | **Global exception handler with correlation IDs** | `GlobalExceptionHandler.java` | Sanitizes error messages, adds correlation IDs for 500 errors, handles 15+ exception types |
| P-14 | **User deletion pre-condition checks** | `UserService.deleteUser()` | Prevents deletion of users with active bookings, listed cars, or admin role — prevents orphan data |
| P-15 | **Rate limiting on auth endpoints** | `RateLimitingFilter.java` | Granular per-endpoint limits: 3 registrations/5 minutes, 5 logins/minute. Critical tier fails closed |

---

## Top 5 Immediate Actions

### 1. 🔴 Add HMAC Secret (Pepper) to HashUtil for JMBG/PIB Hashing
**Finding**: C-1
**Effort**: 2-4 hours (code change) + migration to re-hash existing records
**Risk of inaction**: A database leak exposes every owner's national ID number in under 20 minutes of GPU time. This is the single highest-impact vulnerability in the registration module.

### 2. 🟠 Harden AttributeEncryptor Legacy ECB Fallback
**Finding**: H-7
**Effort**: 1-2 hours (migration audit + alerting + conditional removal)
**Risk of inaction**: No monitoring on fallback triggers means a key rotation or data corruption event could silently expose PII. Audit migration completeness, add metrics on fallback, and schedule ECB path removal once all rows are AES-GCM.

### 3. 🟠 Sanitize Bio Field in updateProfileSecure()
**Finding**: H-3
**Effort**: 30 minutes
**Risk of inaction**: Stored XSS in user bios affects all users who view the profile. Call `InputSanitizer` patterns for HTML/script stripping.

### 4. 🟠 Fix UserController Exception Handling
**Findings**: H-2, H-5
**Effort**: 1-2 hours
**Risk of inaction**: Exception messages leak internal details (table names, SQL fragments) to clients. Replace raw HashMap with typed DTO. Remove overly-broad RuntimeException catches and let GlobalExceptionHandler handle errors consistently.

### 5. 🟠 Add @PreAuthorize Annotations and Rate Limit Profile Picture Uploads
**Findings**: H-4, H-6
**Effort**: 1 hour
**Risk of inaction**: Missing method-level auth annotations create fragile security; excessive upload rate enables resource exhaustion.

---

## Dimension Coverage Summary

| # | Dimension | Findings | Status |
|---|----------|----------|--------|
| 1 | Business Logic | M-2, M-3, M-9 | 3 findings |
| 2 | Security & Authorization (STRIDE) | C-1, H-1, H-3, M-7, L-5 | 5 findings |
| 3 | Data Integrity & Validation | H-7, M-4, M-6, L-2, L-4 | 5 findings |
| 4 | Architecture & Design Patterns | H-6, M-1, M-5, M-11 | 4 findings |
| 5 | Performance & Scalability | H-4 | 1 finding |
| 6 | Edge Cases & Error Handling | L-6 | 1 finding |
| 7 | P2P Marketplace Concerns | See Platform Gaps (MF-1 through MF-10) | Covered in Platform Gaps |
| 8 | API Contract & Frontend Integration | H-5, M-10, L-3 | 3 findings |
| 9 | Serbian Regulatory / GDPR Compliance | C-1 (ZZPL/GDPR Art 32 — PII hashing inadequate) | Covered under C-1 |
| 10 | Testing Gaps | No test files audited — outside scope of source code audit | Zero findings (not assessed) |
| 11 | Observability & Debugging | H-2, M-8, L-1 | 3 findings |
| 12 | Deployment Safety | A1-A3 assumptions documented | Zero direct findings — env config not auditable |
| 13 | Dependency Security | PasswordPolicyService uses Spring Security BCrypt (maintained). No known CVEs in AES-GCM or SHA-256 usage patterns | Zero findings |

---

*End of audit. All findings reference verified source code. Confidence levels are VERIFIED (code read and confirmed) unless otherwise noted.*
