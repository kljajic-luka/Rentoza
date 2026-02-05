# ACTION PLAN: RENTOZA PRODUCTION READINESS

**Audit Date:** February 5, 2026  
**Target Launch:** Pending fixes  
**Effort Estimation:** Story points (1 SP ≈ 4 hours)

---

## Executive Summary

Based on comprehensive audit findings from:
- [CRITICAL_ISSUES.md](CRITICAL_ISSUES.md) - 12 bugs identified
- [MISSING_FEATURES.md](MISSING_FEATURES.md) - 19 gaps identified  
- [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md) - 72/100 architecture score
- [EDGE_CASE_TESTS.md](EDGE_CASE_TESTS.md) - 65 test scenarios defined

**If this platform launches tomorrow with 1,000 users, what will break first?**

> The handshake race condition (BUG-005) combined with disabled tests (BUG-001) means simultaneous check-in attempts will cause duplicate trip starts, confusing notifications, and potential disputes. The mock payment always succeeding (BUG-002) means you can't test payment failures, leading to first real payment failure being a production incident.

---

## Phase 0: Pre-Launch Blockers (MUST FIX)

**Timeline:** Before any real users  
**Total Effort:** 21 SP (~84 hours / 2 weeks with buffer)

| Priority | Issue ID | Fix | Effort | Dependencies |
|----------|----------|-----|--------|--------------|
| P0 | BUG-001 | Enable test suite with Testcontainers | 3 SP | None |
| P0 | BUG-002 | Add failure modes to MockPaymentProvider | 2 SP | None |
| P0 | BUG-005 | Pessimistic lock for handshake | 2 SP | BUG-001 (test coverage) |
| P0 | BUG-006 | Catch OptimisticLockException | 2 SP | None |
| P0 | BUG-007 | Block deposit release with pending claims | 3 SP | None |
| P0 | GAP-001 | Implement payment provider toggle | 3 SP | BUG-002 |
| P0 | GAP-002 | Add distributed scheduler lock | 3 SP | None |
| P0 | GAP-017 | Basic admin dashboard | 3 SP | None |

### Detailed Tasks

#### BUG-001: Enable Test Suite
**File:** `CheckInServiceTest.java`, `CheckInServiceStrictTest.java`

```java
// BEFORE
@Disabled("Waiting for test database setup")
class CheckInServiceTest { ... }

// AFTER
@Testcontainers
@SpringBootTest
class CheckInServiceTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }
}
```

**Validation:**
- [ ] All 47 tests pass
- [ ] CI pipeline green
- [ ] Coverage report generated

---

#### BUG-002: Mock Payment Failure Modes
**File:** `MockPaymentProvider.java`

```java
@Value("${payment.mock.force-failure:false}")
private boolean forceFailure;

@Value("${payment.mock.failure-rate:0.0}")
private double failureRate;

@Override
public PaymentResult charge(PaymentRequest request) {
    if (forceFailure || random.nextDouble() < failureRate) {
        return PaymentResult.failure("SIMULATED_FAILURE", "Mock payment failed for testing");
    }
    // existing success logic
}
```

**Validation:**
- [ ] `payment.mock.force-failure=true` causes all payments to fail
- [ ] `payment.mock.failure-rate=0.5` causes ~50% failures
- [ ] Error messages properly propagated to UI

---

#### BUG-005: Pessimistic Lock for Handshake
**File:** `CheckInService.java` line ~545

```java
// BEFORE
Booking booking = bookingRepository.findById(bookingId)
    .orElseThrow(() -> new ResourceNotFoundException("Rezervacija", bookingId));

// AFTER
Booking booking = bookingRepository.findByIdWithPessimisticLock(bookingId)
    .orElseThrow(() -> new ResourceNotFoundException("Rezervacija", bookingId));
```

**File:** `BookingRepository.java` (add method)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
@Query("SELECT b FROM Booking b WHERE b.id = :id")
Optional<Booking> findByIdWithPessimisticLock(@Param("id") Long id);
```

**Validation:**
- [ ] Concurrent handshake test passes (TC-010)
- [ ] 5-second timeout prevents deadlock

---

#### BUG-006: OptimisticLockException Handling
**File:** `GlobalExceptionHandler.java`

```java
@ExceptionHandler(OptimisticLockException.class)
public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockException ex) {
    log.warn("Concurrent modification detected", ex);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse("CONCURRENT_MODIFICATION", 
            "Data was modified by another request. Please refresh and try again."));
}
```

**Validation:**
- [ ] Concurrent booking update returns 409
- [ ] UI shows retry message

---

#### BUG-007: Block Deposit Release with Pending Claims
**File:** `DepositReleaseService.java` (or equivalent)

```java
public void releaseDeposit(Long bookingId) {
    Booking booking = bookingRepository.findById(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
    
    // NEW CHECK
    boolean hasPendingClaims = damageClaimRepository.existsByBookingIdAndStatusIn(
        bookingId, 
        List.of(ClaimStatus.PENDING, ClaimStatus.UNDER_REVIEW, ClaimStatus.DISPUTED)
    );
    
    if (hasPendingClaims) {
        throw new IllegalStateException("Cannot release deposit: pending damage claims exist");
    }
    
    // existing release logic
}
```

**Validation:**
- [ ] Deposit blocked when claim pending
- [ ] Admin can override with explicit confirmation

---

#### GAP-001: Payment Provider Toggle
**File:** `application.yml`

```yaml
payment:
  provider: ${PAYMENT_PROVIDER:mock}  # mock | stripe
  stripe:
    api-key: ${STRIPE_API_KEY:}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
  mock:
    force-failure: ${PAYMENT_MOCK_FORCE_FAILURE:false}
```

**File:** `PaymentConfig.java`

```java
@Configuration
public class PaymentConfig {
    @Bean
    @ConditionalOnProperty(name = "payment.provider", havingValue = "mock", matchIfMissing = true)
    public PaymentProvider mockPaymentProvider() {
        return new MockPaymentProvider();
    }
    
    @Bean
    @ConditionalOnProperty(name = "payment.provider", havingValue = "stripe")
    public PaymentProvider stripePaymentProvider(StripeProperties props) {
        return new StripePaymentProvider(props);
    }
}
```

**Validation:**
- [ ] Mock provider active in dev
- [ ] Stripe provider active with API key set
- [ ] No code changes needed to switch

---

#### GAP-002: Distributed Scheduler Lock
**Dependency:** Add to `pom.xml`

```xml
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>5.10.0</version>
</dependency>
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-provider-jdbc-template</artifactId>
    <version>5.10.0</version>
</dependency>
```

**File:** `CheckInScheduler.java`

```java
@Scheduled(fixedRate = 60000)
@SchedulerLock(name = "checkNoShowScenarios", lockAtMostFor = "5m", lockAtLeastFor = "1m")
public void checkNoShowScenarios() {
    // existing logic
}
```

**Validation:**
- [ ] Only one instance runs scheduler
- [ ] Lock table created in DB
- [ ] No duplicate no-show processing

---

#### GAP-017: Basic Admin Dashboard
**New Files:**
- `AdminDashboardController.java`
- `AdminDashboardService.java`
- Frontend component (if time permits)

**Minimum Viable Admin:**
```java
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {
    
    @GetMapping("/bookings/stuck")
    public List<BookingDTO> getStuckBookings() {
        // Bookings in non-terminal state for >48h
    }
    
    @GetMapping("/disputes/pending")
    public List<DamageClaimDTO> getPendingDisputes() {
        // Claims needing admin action
    }
    
    @PostMapping("/bookings/{id}/force-complete")
    public BookingDTO forceComplete(@PathVariable Long id, @RequestBody AdminActionDTO action) {
        // Manual override with audit log
    }
}
```

**Validation:**
- [ ] Admin can view stuck bookings
- [ ] Admin can force state transitions
- [ ] All admin actions logged

---

## Phase 1: Launch Week Fixes

**Timeline:** Week 1 post-launch  
**Total Effort:** 18 SP (~72 hours)

| Priority | Issue ID | Fix | Effort |
|----------|----------|-----|--------|
| P1 | BUG-003 | Extend idempotency TTL for payments to 7 days | 1 SP |
| P1 | BUG-004 | Add EXIF tampering detection | 3 SP |
| P1 | BUG-008 | Photo immutability after trip start | 2 SP |
| P1 | GAP-004 | Offline mode queuing | 5 SP |
| P1 | GAP-005 | Multi-photo atomic upload | 3 SP |
| P1 | GAP-012 | Notification retry mechanism | 2 SP |
| P1 | GAP-014 | Rate limiting | 2 SP |

---

## Phase 2: Week 2-4 Improvements

**Timeline:** Month 1  
**Total Effort:** 24 SP (~96 hours)

| Priority | Issue ID | Fix | Effort |
|----------|----------|-----|--------|
| P2 | BUG-009 | Integer division fix in late fees | 1 SP |
| P2 | BUG-010 | Timestamp immutability | 2 SP |
| P2 | BUG-011 | Secure cleanup task token | 1 SP |
| P2 | BUG-012 | Fix cancellation enum type safety | 1 SP |
| P2 | GAP-003 | Compensation saga for failed payments | 5 SP |
| P2 | GAP-008 | Photo hash deduplication | 3 SP |
| P2 | GAP-009 | Price locking during checkout | 3 SP |
| P2 | GAP-010 | Evidence timestamp proofing | 3 SP |
| P2 | GAP-013 | Circuit breaker for externals | 3 SP |
| P2 | GAP-019 | Database-level overlap constraint | 2 SP |

---

## Phase 3: Month 2+ Enhancements

**Timeline:** After stable launch  
**Total Effort:** 25 SP (~100 hours)

| Priority | Issue ID | Fix | Effort |
|----------|----------|-----|--------|
| P3 | GAP-006 | Real-time GPS streaming | 8 SP |
| P3 | GAP-007 | Digital key integration | 8 SP |
| P3 | GAP-011 | ML photo quality scoring | 5 SP |
| P3 | GAP-015 | Dynamic pricing | 3 SP |
| P3 | GAP-016 | Partial refund automation | 1 SP |

---

## Implementation Checklist

### Pre-Launch Validation Gate

- [ ] All P0 issues fixed and merged
- [ ] Test coverage > 80% on critical paths
- [ ] Load test: 100 concurrent users
- [ ] Failover test: Database restart during booking
- [ ] Manual test: Full booking flow (10 times)
- [ ] Security scan: No critical vulnerabilities

### Launch Day Monitoring

- [ ] Error rate dashboard
- [ ] Payment failure alerts
- [ ] Stuck booking alerts (>2h in transitional state)
- [ ] On-call rotation established
- [ ] Runbook reviewed by all engineers

### Post-Launch Reviews

- [ ] Day 1: Hourly metrics review
- [ ] Day 3: Payment reconciliation
- [ ] Day 7: Dispute volume analysis
- [ ] Day 14: Performance baseline
- [ ] Day 30: First month retrospective

---

## Risk Mitigation

### High-Risk Scenarios

| Scenario | Mitigation | Fallback |
|----------|------------|----------|
| Payment provider outage | Circuit breaker + retry | Manual booking confirmation |
| Photo storage outage | Queue uploads locally | Accept text description |
| Database connection loss | Connection pooling + retry | Maintenance mode |
| DDoS attack | Rate limiting + WAF | Geo-blocking |

### Rollback Plan

1. **Database migrations:** All reversible with down scripts
2. **Code deployments:** Blue-green deployment on Cloud Run
3. **Config changes:** Environment variables, no deploy needed
4. **Feature flags:** Disable problematic features without deploy

---

## Team Allocation Recommendation

| Role | Phase 0 | Phase 1 | Phase 2 |
|------|---------|---------|---------|
| Backend Senior | 60% | 40% | 30% |
| Backend Mid | 40% | 60% | 50% |
| Frontend | 20% | 30% | 20% |
| DevOps | 30% | 20% | 20% |
| QA | 50% | 40% | 30% |

---

## Success Criteria

### Minimum Viable Launch
- [ ] 0 P0 bugs remaining
- [ ] 100% of happy path tests passing
- [ ] <1% payment failure rate (simulated)
- [ ] <5s average API response time

### Target State (Month 1)
- [ ] 0 P1 bugs remaining
- [ ] 90% test coverage
- [ ] <0.1% dispute escalation rate
- [ ] 99.9% uptime

### Excellence Target (Month 3)
- [ ] Full real-time GPS integration
- [ ] Automated fraud detection
- [ ] ML-powered damage assessment
- [ ] 99.99% uptime

---

## Appendix: Dependency Graph

```
BUG-001 (Enable Tests)
    └── BUG-005 (Handshake Lock) - needs test verification
    └── BUG-006 (Optimistic Lock) - needs test verification
    └── BUG-007 (Deposit Block) - needs test verification

BUG-002 (Mock Payment Failure)
    └── GAP-001 (Provider Toggle) - needs failure modes first

GAP-002 (Scheduler Lock)
    └── Independent - can parallel with others

GAP-017 (Admin Dashboard)
    └── Independent - critical for ops visibility
```

**Recommended Execution Order:**
1. BUG-001, BUG-002, GAP-002, GAP-017 (parallel)
2. BUG-005, BUG-006, BUG-007 (after tests enabled)
3. GAP-001 (after mock provider enhanced)

---

## Conclusion

The Rentoza platform has a solid foundation but requires focused effort on the 8 P0 items before any production traffic. The estimated 21 story points (~2 weeks with one senior + one mid developer) is achievable.

**Key Message:** The disabled test suite is the single biggest risk multiplier. Every other fix is harder to validate without it. Start there.

**Bottom Line:** With Phase 0 complete, Rentoza can safely handle 1,000 users. Without it, the first busy weekend will generate disputes that undermine platform trust.
