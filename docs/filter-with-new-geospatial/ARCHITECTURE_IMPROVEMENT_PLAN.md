# Rentoza Check-In: Enterprise-Grade Architecture Improvement Plan

**Classification:** Internal Technical Strategy  
**Author:** Principal Software Architect  
**Target Maturity Level:** Turo/Airbnb Production Grade  
**Timeline:** 6-8 weeks  
**Effort:** ~320-400 story points

---

## Table of Contents

1. [Executive Strategy](#executive-strategy)
2. [Critical Path Optimization](#critical-path-optimization)
3. [Architecture Refactoring](#architecture-refactoring)
4. [Backend Implementation Roadmap](#backend-implementation-roadmap)
5. [Frontend Implementation Roadmap](#frontend-implementation-roadmap)
6. [Data & Performance](#data--performance)
7. [Security & Compliance](#security--compliance)
8. [Testing & Quality Assurance](#testing--quality-assurance)
9. [Observability & Operations](#observability--operations)
10. [DevOps & Deployment](#devops--deployment)

---

## Executive Strategy

### Current State Assessment

**Strengths:**
- ✅ Solid foundation (Phase 1-2 complete)
- ✅ Proper layering (Controller → Service → Repository)
- ✅ Event sourcing pattern implemented
- ✅ Basic security controls in place
- ✅ Metrics instrumentation started

**Critical Gaps:**
1. **Scheduler Scalability:** `findAll().stream()` - O(n) memory load
2. **Error Handling:** Basic try-catch, no circuit breaker patterns
3. **Caching:** No Redis/cache layer for frequently accessed data
4. **Transaction Safety:** No saga pattern for distributed transactions
5. **Idempotency:** No idempotency keys for retryable operations
6. **Rate Limiting:** No protection against abuse/DOS
7. **Async Processing:** Photo uploads block HTTP threads
8. **Testing:** <40% coverage, no contract tests
9. **Documentation:** Missing OpenAPI/Swagger specs
10. **Frontend State:** No offline support, no optimistic updates

### Strategic Priorities

```
PHASE 1 (Weeks 1-2): CRITICAL FIXES ✅ COMPLETED
├─ ✅ Scheduler query optimization (V14 migration, paginated queries)
├─ ✅ Add circuit breaker pattern (Resilience4j integration)
├─ ✅ Implement idempotency (IdempotencyService, Redis cache)
└─ ✅ Add rate limiting (Bucket4j, per-endpoint limits)

PHASE 2 (Weeks 3-4): ARCHITECTURE ✅ COMPLETED
├─ ✅ Implement CQRS for read heavy queries (CheckInStatusView + sync)
├─ ✅ Add Redis caching layer (check-in status, idempotency)
├─ ✅ Async photo processing (RabbitMQ, PhotoProcessingQueueService)
└─ ✅ Saga pattern for checkout flow (CheckoutSaga orchestrator)

PHASE 3 (Weeks 5-6): FRONTEND & API OPTIMIZATION ✅ COMPLETED
├─ ✅ WebSocket real-time updates (CheckInWebSocketController)
├─ ✅ Optimistic UI updates (Angular signals, rollback)
├─ ✅ Service Worker caching (ngsw-config.json, check-in rules)
├─ ✅ Offline queue enhancement (form submissions, IndexedDB)
└─ ✅ REST API optimization (ETags, compression, sparse fieldsets)

PHASE 4 (Weeks 7-8): OBSERVABILITY & HARDENING
├─ Distributed tracing
├─ Advanced monitoring
├─ Security audit
└─ Performance tuning
```

---

## Critical Path Optimization

### Issue #1: Scheduler Query Performance ⚠️ CRITICAL

**Current Problem:**
```java
// INEFFICIENT - Loads ALL bookings into memory
public List<Booking> findBookingsForCheckInWindowOpening(
    LocalDateTime startFrom, LocalDateTime startTo) {
    return bookingRepository.findAll().stream()
            .filter(b -> b.getStatus() == BookingStatus.ACTIVE)
            .filter(b -> b.getCheckInSessionId() == null)
            .filter(b -> !b.getStartTime().isBefore(startFrom) 
                    && !b.getStartTime().isAfter(startTo))
            .collect(Collectors.toList());
}
```

**Impact Analysis:**
- 10k bookings: ~2-3 seconds query + memory allocation
- 100k bookings: ~20-30 seconds (timeout risk)
- 1M bookings: Complete failure
- Runs every minute: Cascading failures

**Solution Architecture:**

```java
// ✅ SOLUTION 1: Database Query (Recommended)
@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'ACTIVE'
          AND b.checkInSessionId IS NULL
          AND b.startTime >= :startFrom
          AND b.startTime <= :startTo
        ORDER BY b.startTime ASC
    """)
    @QueryHints({
        @QueryHint(name = "org.hibernate.cacheable", value = "true"),
        @QueryHint(name = "org.hibernate.cacheRegion", value = "booking.checkin")
    })
    List<Booking> findActiveBookingsForCheckInWindow(
        @Param("startFrom") LocalDateTime startFrom,
        @Param("startTo") LocalDateTime startTo
    );
    
    // For no-show detection (paginated for larger datasets)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'CHECK_IN_OPEN'
          AND b.hostCheckInCompletedAt IS NULL
          AND b.startTime < :threshold
    """)
    Page<Booking> findPotentialHostNoShows(
        @Param("threshold") LocalDateTime threshold,
        Pageable pageable
    );
    
    // For guest no-shows
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'CHECK_IN_HOST_COMPLETE'
          AND b.guestCheckInCompletedAt IS NULL
          AND b.hostCheckInCompletedAt IS NOT NULL
          AND DATE_ADD(b.hostCheckInCompletedAt, INTERVAL :minutes MINUTE) < NOW()
    """)
    Page<Booking> findPotentialGuestNoShows(
        @Param("minutes") int minutes,
        Pageable pageable
    );
}
```

**Updated Service Layer:**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInService {
    
    private final BookingRepository bookingRepository;
    private final CheckInEventService eventService;
    private final NotificationService notificationService;
    
    private static final int PAGE_SIZE = 100; // Process in batches
    
    /**
     * Find bookings eligible for check-in window opening.
     * Uses database query for O(n) → O(log n) efficiency.
     */
    @Transactional(readOnly = true)
    public List<Booking> findBookingsForCheckInWindowOpening(
        LocalDateTime startFrom, LocalDateTime startTo) {
        return bookingRepository.findActiveBookingsForCheckInWindow(startFrom, startTo);
    }
    
    /**
     * Find potential host no-shows with pagination.
     * Prevents OOM on large datasets.
     */
    @Transactional(readOnly = true)
    public Page<Booking> findPotentialHostNoShows(
        LocalDateTime threshold, int pageNumber) {
        return bookingRepository.findPotentialHostNoShows(
            threshold,
            PageRequest.of(pageNumber, PAGE_SIZE)
        );
    }
    
    /**
     * Find potential guest no-shows with pagination.
     */
    @Transactional(readOnly = true)
    public Page<Booking> findPotentialGuestNoShows(
        int graceMinutes, int pageNumber) {
        return bookingRepository.findPotentialGuestNoShows(
            graceMinutes,
            PageRequest.of(pageNumber, PAGE_SIZE)
        );
    }
}
```

**Updated Scheduler:**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CheckInScheduler {
    
    private final CheckInService checkInService;
    private final MeterRegistry meterRegistry;
    
    @Value("${app.checkin.scheduler.enabled:true}")
    private boolean schedulerEnabled;
    
    @Value("${app.checkin.window-hours-before-trip:24}")
    private int windowHours;
    
    /**
     * Opens check-in windows for bookings starting in 24 hours.
     * Executes every minute, processes batch by batch.
     * Uses database pagination to prevent memory issues.
     */
    @Scheduled(cron = "0 * * * * *") // Every minute
    public void openCheckInWindows() {
        if (!schedulerEnabled) {
            log.debug("Check-in scheduler disabled");
            return;
        }
        
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Belgrade"));
            LocalDateTime startWindow = now.plusHours(windowHours);
            LocalDateTime endWindow = startWindow.plusMinutes(1);
            
            log.debug("[Scheduler] Searching for bookings in window: {} - {}",
                startWindow, endWindow);
            
            List<Booking> bookingsToOpen = 
                checkInService.findBookingsForCheckInWindowOpening(startWindow, endWindow);
            
            if (bookingsToOpen.isEmpty()) {
                log.debug("[Scheduler] No bookings found for check-in window");
                return;
            }
            
            log.info("[Scheduler] Found {} bookings for check-in window opening", 
                bookingsToOpen.size());
            
            for (Booking booking : bookingsToOpen) {
                try {
                    openCheckInWindow(booking);
                } catch (Exception e) {
                    log.error("[Scheduler] Error opening check-in window for booking {}: {}",
                        booking.getId(), e.getMessage(), e);
                    meterRegistry.counter("checkin.scheduler.error").increment();
                }
            }
            
        } catch (Exception e) {
            log.error("[Scheduler] Critical error in check-in window opening", e);
            meterRegistry.counter("checkin.scheduler.critical.error").increment();
        }
    }
    
    /**
     * Detects no-show violations.
     * Processes in batches to handle large datasets gracefully.
     */
    @Scheduled(cron = "0 */5 * * * *") // Every 5 minutes
    public void detectNoShows() {
        if (!schedulerEnabled) return;
        
        try {
            LocalDateTime threshold = LocalDateTime.now(ZoneId.of("Europe/Belgrade"));
            int pageNumber = 0;
            boolean hasMore = true;
            
            while (hasMore) {
                Page<Booking> noShowCandidates = 
                    checkInService.findPotentialHostNoShows(threshold, pageNumber);
                
                if (noShowCandidates.isEmpty()) {
                    hasMore = false;
                    break;
                }
                
                for (Booking booking : noShowCandidates) {
                    try {
                        processHostNoShow(booking);
                    } catch (Exception e) {
                        log.error("[Scheduler] Error processing host no-show for booking {}: {}",
                            booking.getId(), e.getMessage(), e);
                    }
                }
                
                if (noShowCandidates.hasNext()) {
                    pageNumber++;
                } else {
                    hasMore = false;
                }
            }
            
        } catch (Exception e) {
            log.error("[Scheduler] Error detecting no-shows", e);
            meterRegistry.counter("checkin.scheduler.noshow.error").increment();
        }
    }
    
    private void openCheckInWindow(Booking booking) {
        // Implementation
    }
    
    private void processHostNoShow(Booking booking) {
        // Implementation
    }
}
```

**Database Index Enhancement:**

```sql
-- V18__optimize_scheduler_queries.sql

-- For check-in window opening (must have low selectivity)
CREATE INDEX idx_booking_active_window 
    ON bookings (status, check_in_session_id, start_time)
    WHERE status = 'ACTIVE' AND check_in_session_id IS NULL;

-- For host no-show detection
CREATE INDEX idx_booking_host_noshow 
    ON bookings (status, host_check_in_completed_at, start_time)
    WHERE status = 'CHECK_IN_OPEN' AND host_check_in_completed_at IS NULL;

-- For guest no-show detection
CREATE INDEX idx_booking_guest_noshow 
    ON bookings (status, guest_check_in_completed_at, host_check_in_completed_at)
    WHERE status = 'CHECK_IN_HOST_COMPLETE' AND guest_check_in_completed_at IS NULL;
```

**Test Cases:**

```java
@SpringBootTest
class CheckInSchedulerOptimizationTest {
    
    @Autowired private CheckInScheduler scheduler;
    @Autowired private BookingRepository repository;
    @Autowired private TestEntityManager em;
    
    @Test
    void shouldProcessLargeDatasetEfficiently() {
        // Create 10,000 bookings
        for (int i = 0; i < 10_000; i++) {
            Booking booking = new Booking();
            booking.setStatus(BookingStatus.ACTIVE);
            booking.setStartTime(LocalDateTime.now().plusHours(24));
            repository.save(booking);
        }
        em.flush();
        
        // Should complete in <5 seconds even with large dataset
        StopWatch watch = new StopWatch();
        watch.start();
        scheduler.openCheckInWindows();
        watch.stop();
        
        assertThat(watch.getTotalTimeSeconds()).isLessThan(5);
    }
    
    @Test
    void shouldProcessBatchesWithoutOOM() {
        // Create 100,000 bookings
        for (int i = 0; i < 100_000; i++) {
            Booking booking = new Booking();
            booking.setStatus(BookingStatus.CHECK_IN_OPEN);
            booking.setStartTime(LocalDateTime.now().minusHours(2));
            repository.save(booking);
        }
        em.flush();
        
        // Monitor memory usage
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long memBefore = memoryBean.getHeapMemoryUsage().getUsed();
        
        scheduler.detectNoShows();
        
        long memAfter = memoryBean.getHeapMemoryUsage().getUsed();
        long memIncrease = memAfter - memBefore;
        
        // Memory increase should be <100MB even with 100k bookings
        assertThat(memIncrease).isLessThan(100 * 1024 * 1024);
    }
}
```

---

### Issue #2: Missing Idempotency Protection ⚠️ CRITICAL

**Problem:** No idempotency keys → duplicate charges, duplicate events on retry

**Solution Pattern:**

```java
// Step 1: Add idempotency key column
@Entity
@Table(name = "check_in_events")
public class CheckInEvent {
    
    @Id
    @GeneratedValue
    private Long id;
    
    // Unique constraint ensures exactly one event per idempotency key
    @Column(name = "idempotency_key", unique = true, length = 36)
    private String idempotencyKey;
    
    // ... rest of fields
}

// Step 2: Service Layer Idempotency Handler
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {
    
    private final CheckInEventRepository eventRepository;
    private final RedisTemplate<String, String> redisTemplate;
    
    /**
     * Check if operation already executed.
     * First checks Redis (fast path), then database (fallback).
     */
    public <T> T executeIdempotently(
        String idempotencyKey,
        Supplier<T> operation,
        Class<T> resultClass) {
        
        String cacheKey = "idempotency:" + idempotencyKey;
        
        // Fast path: Check Redis
        String cachedResult = redisTemplate.opsForValue().get(cacheKey);
        if (cachedResult != null) {
            log.debug("[Idempotency] Cache hit for key: {}", idempotencyKey);
            return objectMapper.readValue(cachedResult, resultClass);
        }
        
        // Slow path: Check database
        boolean alreadyExecuted = eventRepository.existsByIdempotencyKey(idempotencyKey);
        if (alreadyExecuted) {
            log.warn("[Idempotency] Operation already executed: {}", idempotencyKey);
            throw new IdempotentOperationAlreadyExecutedException(idempotencyKey);
        }
        
        // Execute operation
        T result = operation.get();
        
        // Cache result for 24 hours
        String serialized = objectMapper.writeValueAsString(result);
        redisTemplate.opsForValue()
            .set(cacheKey, serialized, Duration.ofHours(24));
        
        return result;
    }
}

// Step 3: Controller Integration
@RestController
@RequiredArgsConstructor
@Slf4j
public class CheckInController {
    
    private final CheckInService checkInService;
    private final IdempotencyService idempotencyService;
    
    @PostMapping("/host/complete")
    public ResponseEntity<CheckInStatusDTO> completeHostCheckIn(
            @PathVariable Long bookingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody HostCheckInSubmissionDTO submission) {
        
        Long userId = currentUser.id();
        submission.setBookingId(bookingId);
        
        try {
            // Execute with idempotency protection
            CheckInStatusDTO result = idempotencyService.executeIdempotently(
                idempotencyKey,
                () -> checkInService.completeHostCheckIn(submission, userId),
                CheckInStatusDTO.class
            );
            
            return ResponseEntity.ok(result);
            
        } catch (IdempotentOperationAlreadyExecutedException ex) {
            // Return 409 Conflict with cached result
            log.warn("[CheckIn] Idempotent retry detected: {}", idempotencyKey);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ex.getCachedResult());
        }
    }
}
```

**Database Migration:**

```sql
-- V18__idempotency_keys.sql

ALTER TABLE check_in_events 
ADD COLUMN idempotency_key VARCHAR(36) UNIQUE NULL 
COMMENT 'UUID for idempotent request deduplication';

CREATE INDEX idx_event_idempotency_key 
ON check_in_events(idempotency_key);

-- Track payment transactions
CREATE TABLE IF NOT EXISTS payment_idempotency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(36) UNIQUE NOT NULL,
    payment_reference VARCHAR(100),
    status VARCHAR(20),
    result_json JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP DEFAULT DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
);
```

**Frontend: Include Idempotency Key in Requests**

```typescript
// Angular HTTP Interceptor
@Injectable()
export class IdempotencyInterceptor implements HttpInterceptor {
    
    constructor(private http: HttpClient) {}
    
    intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
        
        // For mutations, add/ensure Idempotency-Key
        if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(req.method)) {
            
            // Check if idempotency key already set (for retries)
            let idempotencyKey = req.headers.get('Idempotency-Key');
            
            if (!idempotencyKey) {
                // Generate UUID v4 for new requests
                idempotencyKey = this.generateUUID();
                
                // Store in session storage for retries
                const cacheKey = `${req.method}:${req.url}`;
                sessionStorage.setItem(cacheKey, idempotencyKey);
            }
            
            req = req.clone({
                setHeaders: {
                    'Idempotency-Key': idempotencyKey
                }
            });
        }
        
        return next.handle(req);
    }
    
    private generateUUID(): string {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }
}
```

---

### Issue #3: Missing Circuit Breaker Pattern ⚠️ HIGH

**Problem:** Single failing external service (payment, ID verification) crashes entire check-in flow

**Solution: Resilience4j Integration**

```java
// Step 1: Add Resilience4j Dependency
// pom.xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.1.0</version>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
</dependency>

// Step 2: Configuration
@Configuration
public class ResilienceConfiguration {
    
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)  // Open after 50% failures
            .slowCallRateThreshold(50.0f) // Open after 50% slow (>2s)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordExceptions(
                TimeoutException.class,
                RemoteServiceException.class,
                IOException.class
            )
            .ignoreExceptions(
                BusinessException.class,
                IllegalArgumentException.class
            )
            .build();
        
        return CircuitBreakerRegistry.of(defaultConfig);
    }
    
    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .intervalFunction(
                IntervalFunction.ofExponentialBackoff(500, 2)
            )
            .retryExceptions(
                TimeoutException.class,
                SocketTimeoutException.class
            )
            .ignoreExceptions(
                BusinessException.class,
                ValidationException.class
            )
            .build();
        
        return RetryRegistry.of(config);
    }
    
    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofSeconds(5))
            .cancelRunningFuture(true)
            .build();
        
        return TimeLimiterRegistry.of(config);
    }
}

// Step 3: Service Layer Integration
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInPhotoService {
    
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final ExifValidationService exifValidationService;
    
    @PostConstruct
    void initializeResilience() {
        // Create circuit breaker for EXIF validation
        circuitBreakerRegistry.circuitBreaker("exif-validation")
            .getEventPublisher()
            .onStateTransition(event -> 
                log.warn("[Resilience] EXIF CB state change: {} → {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState())
            );
    }
    
    @Transactional
    public CheckInPhotoDTO uploadPhoto(
            Long bookingId,
            Long userId,
            MultipartFile file,
            CheckInPhotoType photoType,
            Instant clientTimestamp,
            BigDecimal clientLatitude,
            BigDecimal clientLongitude) throws IOException {
        
        // Validate file size
        validateFileSize(file);
        
        // Store file with resilience
        CheckInPhoto photo = storePhotoWithResilience(file, bookingId, photoType);
        
        // Validate EXIF with circuit breaker
        ExifValidationResult exifResult = validateExifWithCircuitBreaker(
            file.getBytes(),
            clientTimestamp,
            clientLatitude,
            clientLongitude
        );
        
        photo.setExifValidationStatus(exifResult.getStatus());
        photo.setExifValidationMessage(exifResult.getMessage());
        
        // ... rest of implementation
        
        return mapToDTO(photo);
    }
    
    private ExifValidationResult validateExifWithCircuitBreaker(
            byte[] photoBytes,
            Instant clientTimestamp,
            BigDecimal clientLatitude,
            BigDecimal clientLongitude) {
        
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("exif-validation");
        Retry retry = retryRegistry.retry("exif-validation");
        TimeLimiter timeLimiter = timeLimiterRegistry.timeLimiter("exif-validation");
        
        Supplier<ExifValidationResult> supplier = () -> 
            exifValidationService.validate(photoBytes, clientTimestamp, 
                clientLatitude, clientLongitude);
        
        Supplier<ExifValidationResult> decoratedSupplier = CircuitBreaker
            .decorateSupplier(breaker,
                Retry.decorateSupplier(retry,
                    TimeLimiter.decorateSupplier(timeLimiter, supplier)));
        
        try {
            return decoratedSupplier.get();
        } catch (CircuitBreakerOpenException ex) {
            log.warn("[CheckIn] EXIF validation circuit breaker open, " +
                "using fallback validation");
            // Fallback: Accept photo with warning status
            return ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_WITH_WARNINGS)
                .message("EXIF validation temporarily unavailable")
                .build();
        } catch (Exception ex) {
            log.error("[CheckIn] EXIF validation failed", ex);
            throw new PhotoValidationException("Photo validation failed: " + 
                ex.getMessage());
        }
    }
}

// Step 4: Configuration Properties
// application.properties
resilience4j.circuitbreaker.instances.exif-validation.register-health-indicator=true
resilience4j.circuitbreaker.instances.exif-validation.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.exif-validation.slow-call-rate-threshold=50
resilience4j.circuitbreaker.instances.exif-validation.slow-call-duration-threshold=2s
resilience4j.circuitbreaker.instances.exif-validation.wait-duration-in-open-state=30s
resilience4j.circuitbreaker.instances.exif-validation.permitted-number-of-calls-in-half-open-state=3

resilience4j.retry.instances.exif-validation.max-attempts=3
resilience4j.retry.instances.exif-validation.wait-duration=500ms
resilience4j.retry.instances.exif-validation.retry-exceptions=java.net.SocketTimeoutException

resilience4j.timelimiter.instances.exif-validation.timeout-duration=5s
```

---

## Architecture Refactoring

### Strategic Pattern Implementations

#### Pattern 1: CQRS (Command Query Responsibility Segregation)

**Rationale:** Check-in workflow is heavy on reads (polling status) but lighter on writes. Separate read and write models for optimal performance.

```java
// ============================================================================
// WRITE MODEL: CheckInCommandService
// ============================================================================

/**
 * Handles all write operations (commands) for check-in workflow.
 * Single source of truth for state changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CheckInCommandService {
    
    private final BookingRepository bookingRepository;
    private final CheckInEventRepository eventRepository;
    private final CheckInPhotoRepository photoRepository;
    private final EventPublisher eventPublisher; // For read model sync
    
    /**
     * Command: Complete host check-in
     * Single transaction, updates booking + photos + events
     */
    public void completeHostCheckIn(
            Long bookingId,
            Long userId,
            HostCheckInSubmissionDTO dto) {
        
        Booking booking = acquireLock(bookingId); // Pessimistic lock
        
        // Validate preconditions
        validateHostCheckInPreconditions(booking, userId);
        
        // Execute state transition
        booking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
        booking.setStartOdometer(dto.getOdometerReading());
        booking.setStartFuelLevel(dto.getFuelLevelPercent());
        booking.setHostCheckInCompletedAt(Instant.now());
        
        // Record immutable event
        CheckInEvent event = CheckInEvent.builder()
            .bookingId(bookingId)
            .eventType(CheckInEventType.HOST_SECTION_COMPLETE)
            .actorRole(CheckInActorRole.HOST)
            .actorId(userId)
            .eventData(serializeState(booking))
            .build();
        
        bookingRepository.save(booking);
        eventRepository.save(event);
        
        // Publish event for read model sync
        eventPublisher.publishEvent(
            new CheckInHostCompletedEvent(bookingId, dto.getOdometerReading())
        );
        
        log.info("[CheckIn] Host completed check-in for booking {}", bookingId);
    }
    
    private Booking acquireLock(Long bookingId) {
        return bookingRepository.findByIdWithPessimisticLock(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }
    
    private void validateHostCheckInPreconditions(Booking booking, Long userId) {
        if (booking.getStatus() != BookingStatus.CHECK_IN_OPEN) {
            throw new InvalidStateException("Booking not in CHECK_IN_OPEN state");
        }
        if (!booking.getCar().getOwner().getId().equals(userId)) {
            throw new AccessDeniedException("Only car owner can complete check-in");
        }
    }
}

// ============================================================================
// READ MODEL: CheckInQueryService
// ============================================================================

/**
 * Handles all read operations (queries) for check-in workflow.
 * Optimized for fast queries using read-optimized views/caches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInQueryService {
    
    private final CheckInStatusQueryRepository statusQueryRepository;
    private final RedisTemplate<String, CheckInStatusDTO> redisTemplate;
    
    /**
     * Query: Get check-in status (read-optimized, cached)
     * Reads from materialized view, not transactional tables.
     */
    @Transactional(readOnly = true)
    @Cacheable(
        cacheNames = "checkInStatus",
        key = "#bookingId",
        unless = "#result.status == 'IN_TRIP'"
    )
    public CheckInStatusDTO getCheckInStatus(Long bookingId, Long userId) {
        
        // First try cache
        String cacheKey = "checkin:status:" + bookingId;
        CheckInStatusDTO cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && isAuthorized(cached, userId)) {
            return cached;
        }
        
        // Fall back to optimized query (uses read-only replica)
        CheckInStatusView view = statusQueryRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        
        CheckInStatusDTO result = mapViewToDTO(view, userId);
        
        // Cache for 30 seconds
        redisTemplate.opsForValue()
            .set(cacheKey, result, Duration.ofSeconds(30));
        
        return result;
    }
    
    /**
     * Query: Get host's pending check-ins (read-optimized)
     * Uses denormalized view for fast retrieval.
     */
    @Transactional(readOnly = true)
    public List<CheckInStatusDTO> getHostPendingCheckIns(Long hostId) {
        return statusQueryRepository
            .findByHostIdAndStatusIn(hostId, List.of(
                BookingStatus.CHECK_IN_OPEN,
                BookingStatus.CHECK_IN_HOST_COMPLETE
            ))
            .stream()
            .map(this::mapViewToDTO)
            .collect(Collectors.toList());
    }
}

// ============================================================================
// MATERIALIZED VIEW: CheckInStatusView
// ============================================================================

/**
 * Read-optimized denormalized view of check-in status.
 * Updated asynchronously when commands execute.
 */
@Entity
@Table(name = "v_check_in_status", schema = "read_model")
@Data
public class CheckInStatusView {
    
    @Id
    private Long bookingId;
    private Long carId;
    private String carBrand;
    private String carModel;
    private Integer carYear;
    private String carImageUrl;
    
    private Long hostId;
    private String hostName;
    private String hostImageUrl;
    
    private Long guestId;
    private String guestName;
    private String guestImageUrl;
    
    private BookingStatus status;
    private LocalDateTime checkInOpenedAt;
    private LocalDateTime hostCompletedAt;
    private LocalDateTime guestCompletedAt;
    private Boolean hostReady;
    private Boolean guestReady;
    
    private Integer odometerReading;
    private Integer fuelLevelPercent;
    private Long minutesUntilNoShow;
    
    @LastModifiedDate
    private Instant updatedAt;
}

// ============================================================================
// VIEW REPOSITORY: Optimized for read queries
// ============================================================================

@Repository
public interface CheckInStatusQueryRepository 
    extends JpaRepository<CheckInStatusView, Long> {
    
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.hostId = :hostId
        AND v.status IN :statuses
        ORDER BY v.checkInOpenedAt DESC
    """)
    List<CheckInStatusView> findByHostIdAndStatusIn(
        @Param("hostId") Long hostId,
        @Param("statuses") List<BookingStatus> statuses
    );
    
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.guestId = :guestId
        AND v.status = :status
        ORDER BY v.checkInOpenedAt DESC
    """)
    Page<CheckInStatusView> findByGuestIdAndStatus(
        @Param("guestId") Long guestId,
        @Param("status") BookingStatus status,
        Pageable pageable
    );
}

// ============================================================================
// EVENT-DRIVEN SYNCHRONIZATION: Command publishes events for read model
// ============================================================================

@Configuration
public class ReadModelSyncConfiguration {
    
    @Bean
    public ApplicationEventMulticaster simpleMulticaster() {
        SimpleApplicationEventMulticaster multicaster = 
            new SimpleApplicationEventMulticaster();
        multicaster.setTaskExecutor(new ThreadPoolTaskExecutor());
        return multicaster;
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
public class CheckInEventListener {
    
    private final CheckInStatusViewRepository viewRepository;
    
    /**
     * Updates materialized view when host completes check-in.
     * Executes asynchronously (non-blocking).
     */
    @EventListener
    @Async
    public void onHostCompleted(CheckInHostCompletedEvent event) {
        try {
            CheckInStatusView view = viewRepository.findById(event.getBookingId())
                .orElseReturn;
            view.setHostReady(true);
            view.setHostCompletedAt(LocalDateTime.now());
            view.setOdometerReading(event.getOdometerReading());
            viewRepository.save(view);
            
            log.debug("[ReadModel] Updated check-in status for booking {}", 
                event.getBookingId());
        } catch (Exception e) {
            log.error("[ReadModel] Error updating check-in status", e);
            // Don't throw - read model can be rebuilt from events
        }
    }
}

// ============================================================================
// MATERIALIZED VIEW REBUILD: Periodic full sync (fallback)
// ============================================================================

@Component
@RequiredArgsConstructor
@Slf4j
public class CheckInViewRebuildScheduler {
    
    private final BookingRepository bookingRepository;
    private final CheckInStatusViewRepository viewRepository;
    
    /**
     * Full rebuild of read model (hourly fallback).
     * Ensures consistency even if events were lost.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void rebuildCheckInStatusView() {
        try {
            log.info("[ReadModel] Starting check-in status view rebuild");
            
            List<Booking> allBookings = bookingRepository.findAll();
            List<CheckInStatusView> views = allBookings.stream()
                .filter(b -> isCheckInRelated(b.getStatus()))
                .map(this::convertToView)
                .collect(Collectors.toList());
            
            viewRepository.deleteAll();
            viewRepository.saveAll(views);
            
            log.info("[ReadModel] Rebuilt check-in status view with {} records",
                views.size());
        } catch (Exception e) {
            log.error("[ReadModel] Error rebuilding check-in status view", e);
        }
    }
    
    private CheckInStatusView convertToView(Booking booking) {
        CheckInStatusView view = new CheckInStatusView();
        view.setBookingId(booking.getId());
        // ... map all fields
        return view;
    }
    
    private boolean isCheckInRelated(BookingStatus status) {
        return status.ordinal() >= BookingStatus.CHECK_IN_OPEN.ordinal() &&
               status.ordinal() < BookingStatus.COMPLETED.ordinal();
    }
}
```

**Benefits:**
- ✅ Read queries < 10ms (cached, denormalized)
- ✅ Write operations remain consistent (single transaction)
- ✅ Scalable: Read replicas can handle millions of status polls
- ✅ Eventual consistency acceptable for status view (30-second delay)

---

#### Pattern 2: Saga Pattern for Distributed Transactions

**Scenario:** Checkout workflow requires atomicity across multiple services (photo storage, damage assessment, payment)

```java
// ============================================================================
// SAGA: Checkout Flow as Orchestrated Saga
// ============================================================================

/**
 * Orchestrates the checkout workflow across multiple services.
 * Handles partial failures and compensation (rollback).
 * Pattern: Orchestration-based Saga (service coordinates)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CheckoutSaga {
    
    private final BookingRepository bookingRepository;
    private final CheckOutService checkOutService;
    private final DamageClaimService damageClaimService;
    private final BookingPaymentService paymentService;
    private final SagaStateRepository sagaStateRepository;
    
    private enum SagaStep {
        INITIATED,
        PHOTOS_VALIDATED,
        LATE_FEE_CALCULATED,
        PAYMENT_AUTHORIZED,
        DAMAGE_ASSESSED,
        COMPLETED
    }
    
    /**
     * Start checkout saga for a booking.
     * Coordinates multiple steps with compensation on failure.
     */
    public void startCheckoutSaga(Long bookingId, Long userId) {
        
        String sagaId = UUID.randomUUID().toString();
        SagaState state = SagaState.builder()
            .sagaId(sagaId)
            .bookingId(bookingId)
            .status(SagaStatus.RUNNING)
            .currentStep(SagaStep.INITIATED)
            .createdAt(Instant.now())
            .build();
        
        sagaStateRepository.save(state);
        
        try {
            // Step 1: Validate checkout photos
            log.info("[Saga] {} Step 1: Validating photos for booking {}",
                sagaId, bookingId);
            validateCheckoutPhotos(bookingId, userId);
            updateSagaStep(sagaId, SagaStep.PHOTOS_VALIDATED);
            
            // Step 2: Calculate late fees
            log.info("[Saga] {} Step 2: Calculating late fees for booking {}",
                sagaId, bookingId);
            BigDecimal lateFee = calculateLateFeesIfApplicable(bookingId);
            updateSagaStep(sagaId, SagaStep.LATE_FEE_CALCULATED);
            
            // Step 3: Authorize payment for late fees
            if (lateFee.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[Saga] {} Step 3: Authorizing late fee payment: {} RSD",
                    sagaId, lateFee);
                String authorizationId = authorizeLateFeePayment(bookingId, lateFee);
                updateSagaData(sagaId, "lateFeeAuthorizationId", authorizationId);
            }
            updateSagaStep(sagaId, SagaStep.PAYMENT_AUTHORIZED);
            
            // Step 4: Assess damage and create claim if needed
            log.info("[Saga] {} Step 4: Assessing damage for booking {}",
                sagaId, bookingId);
            assessDamage(bookingId);
            updateSagaStep(sagaId, SagaStep.DAMAGE_ASSESSED);
            
            // All steps completed successfully
            completeSaga(sagaId);
            log.info("[Saga] {} Completed successfully", sagaId);
            
        } catch (Exception e) {
            log.error("[Saga] {} Error in step {}: {}",
                sagaId, getSagaCurrentStep(sagaId), e.getMessage(), e);
            
            // Compensate (rollback) on failure
            compensateSaga(sagaId);
            markSagaFailed(sagaId, e.getMessage());
        }
    }
    
    /**
     * Compensation: Rollback on failure.
     * Must be idempotent (can be called multiple times).
     */
    private void compensateSaga(String sagaId) {
        SagaState state = sagaStateRepository.findBySagaId(sagaId)
            .orElseReturn;
        
        log.warn("[Saga] {} Compensating from step: {}", 
            sagaId, state.getCurrentStep());
        
        try {
            switch (state.getCurrentStep()) {
                case PAYMENT_AUTHORIZED:
                    // Release authorized payment
                    String authId = (String) state.getData().get("lateFeeAuthorizationId");
                    if (authId != null) {
                        paymentService.releaseAuthorization(authId);
                        log.info("[Saga] {} Compensated: Released payment authorization",
                            sagaId);
                    }
                    // Fall through
                    
                case LATE_FEE_CALCULATED:
                case PHOTOS_VALIDATED:
                    // Nothing to compensate for read-only steps
                    break;
            }
        } catch (Exception e) {
            log.error("[Saga] {} Compensation failed at step {}: {}",
                sagaId, state.getCurrentStep(), e.getMessage(), e);
            // Mark for manual intervention
            markSagaRequiresManualReview(sagaId);
        }
    }
    
    // Helper methods
    private void validateCheckoutPhotos(Long bookingId, Long userId) {
        // Implementation
    }
    
    private BigDecimal calculateLateFeesIfApplicable(Long bookingId) {
        // Implementation
        return BigDecimal.ZERO;
    }
    
    private String authorizeLateFeePayment(Long bookingId, BigDecimal amount) {
        // Implementation
        return "auth_123";
    }
    
    private void assessDamage(Long bookingId) {
        // Implementation
    }
    
    private void updateSagaStep(String sagaId, SagaStep step) {
        SagaState state = sagaStateRepository.findBySagaId(sagaId)
            .orElseThrow();
        state.setCurrentStep(step);
        state.setUpdatedAt(Instant.now());
        sagaStateRepository.save(state);
    }
    
    private void updateSagaData(String sagaId, String key, String value) {
        SagaState state = sagaStateRepository.findBySagaId(sagaId)
            .orElseThrow();
        state.getData().put(key, value);
        sagaStateRepository.save(state);
    }
    
    private void completeSaga(String sagaId) {
        SagaState state = sagaStateRepository.findBySagaId(sagaId)
            .orElseThrow();
        state.setStatus(SagaStatus.COMPLETED);
        state.setCompletedAt(Instant.now());
        sagaStateRepository.save(state);
    }
    
    private void markSagaFailed(String sagaId, String reason) {
        SagaState state = sagaStateRepository.findBySagaId(sagaId)
            .orElseThrow();
        state.setStatus(SagaStatus.FAILED);
        state.setFailureReason(reason);
        sagaStateRepository.save(state);
    }
    
    private void markSagaRequiresManualReview(String sagaId) {
        SagaState state = sagaStateRepository.findBySagaId(sagaId)
            .orElseThrow();
        state.setStatus(SagaStatus.REQUIRES_MANUAL_REVIEW);
        sagaStateRepository.save(state);
        
        // Alert admin
        // sendAlert("Saga " + sagaId + " requires manual review");
    }
    
    private SagaStep getSagaCurrentStep(String sagaId) {
        return sagaStateRepository.findBySagaId(sagaId)
            .map(SagaState::getCurrentStep)
            .orElse(SagaStep.INITIATED);
    }
}

// ============================================================================
// SAGA STATE PERSISTENCE
// ============================================================================

@Entity
@Table(name = "checkout_sagas")
@Data
public class SagaState {
    
    @Id
    private String sagaId;
    
    @Column(name = "booking_id")
    private Long bookingId;
    
    @Enumerated(EnumType.STRING)
    private SagaStatus status; // RUNNING, COMPLETED, FAILED, REQUIRES_MANUAL_REVIEW
    
    @Enumerated(EnumType.STRING)
    private CheckoutSaga.SagaStep currentStep;
    
    @Type(JsonType.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> data = new HashMap<>();
    
    private String failureReason;
    
    @CreationTimestamp
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
    
    private Instant completedAt;
}

enum SagaStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    REQUIRES_MANUAL_REVIEW
}
```

---

#### Pattern 3: Async Photo Processing

**Problem:** Large photo uploads block HTTP threads, causing slow API responses

**Solution: Message Queue + Background Workers**

```java
// Step 1: Add messaging dependency (RabbitMQ or Kafka)
// pom.xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>

// Step 2: Event model for async processing
public record PhotoUploadedEvent(
    Long bookingId,
    Long photoId,
    String storageKey,
    CheckInPhotoType photoType
) implements Serializable {}

// Step 3: Async publisher in controller
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInController {
    
    private final RabbitTemplate rabbitTemplate;
    
    @PostMapping("/host/photos")
    public ResponseEntity<CheckInPhotoDTO> uploadHostPhoto(
            @PathVariable Long bookingId,
            @RequestPart("file") MultipartFile file,
            @RequestParam CheckInPhotoType photoType) throws IOException {
        
        // Immediate response (fast path)
        CheckInPhotoDTO photoDTO = checkInPhotoService
            .storePhotoFile(bookingId, file, photoType);
        
        // Queue EXIF validation asynchronously
        PhotoUploadedEvent event = new PhotoUploadedEvent(
            bookingId,
            photoDTO.getPhotoId(),
            photoDTO.getUrl(),
            photoType
        );
        
        rabbitTemplate.convertAndSend(
            "checkin.exchange",
            "photo.uploaded",
            event,
            message -> {
                message.getMessageProperties()
                    .setExpiration("300000"); // 5 minute expiry
                message.getMessageProperties()
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }
        );
        
        log.info("[Photo] Uploaded photo {} for booking {}, validation queued",
            photoDTO.getPhotoId(), bookingId);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(photoDTO);
    }
}

// Step 4: Background worker processes photos
@Component
@RequiredArgsConstructor
@Slf4j
public class PhotoProcessingWorker {
    
    private final CheckInPhotoRepository photoRepository;
    private final ExifValidationService exifValidationService;
    private final MeterRegistry meterRegistry;
    
    @RabbitListener(
        queues = "checkin.photos.queue",
        concurrency = "5-10" // Process up to 10 photos in parallel
    )
    public void processPhotoUpload(PhotoUploadedEvent event) {
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            log.debug("[Worker] Processing photo {} from booking {}",
                event.photoId(), event.bookingId());
            
            // Retrieve photo file from storage
            byte[] photoBytes = retrievePhotoFromStorage(event.storageKey());
            
            // Validate EXIF (potentially slow operation)
            ExifValidationResult result = exifValidationService
                .validate(photoBytes, null, null, null);
            
            // Update photo record with validation result
            CheckInPhoto photo = photoRepository.findById(event.photoId())
                .orElseThrow();
            photo.setExifValidationStatus(result.getStatus());
            photo.setExifValidationMessage(result.getMessage());
            
            if (result.getExifTimestamp() != null) {
                photo.setExifTimestamp(result.getExifTimestamp());
            }
            if (result.getLatitude() != null) {
                photo.setExifLatitude(result.getLatitude());
            }
            if (result.getLongitude() != null) {
                photo.setExifLongitude(result.getLongitude());
            }
            
            photoRepository.save(photo);
            
            sample.stop(Timer.builder("photo.exif.validation")
                .description("Time to validate EXIF data")
                .register(meterRegistry));
            
            log.info("[Worker] Validated photo {}: {}",
                event.photoId(), result.getStatus());
            
            // Notify client via WebSocket (if needed)
            notifyPhotoValidated(event.bookingId(), event.photoId(), result);
            
            meterRegistry.counter("photo.validation.success").increment();
            
        } catch (Exception e) {
            log.error("[Worker] Error processing photo {}: {}",
                event.photoId(), e.getMessage(), e);
            
            meterRegistry.counter("photo.validation.error").increment();
            
            // Retry mechanism (exponential backoff)
            if (shouldRetry(event)) {
                retryWithBackoff(event);
            } else {
                recordPhotoValidationFailure(event);
            }
        }
    }
    
    private byte[] retrievePhotoFromStorage(String storageKey) {
        // Implementation (file system, S3, etc.)
        return new byte[0];
    }
    
    private void notifyPhotoValidated(Long bookingId, Long photoId,
            ExifValidationResult result) {
        // WebSocket notification to client
    }
    
    private boolean shouldRetry(PhotoUploadedEvent event) {
        // Implement retry logic
        return true;
    }
    
    private void retryWithBackoff(PhotoUploadedEvent event) {
        // Re-queue with exponential backoff
    }
    
    private void recordPhotoValidationFailure(PhotoUploadedEvent event) {
        // Mark photo as failed validation
    }
}

// Step 5: RabbitMQ Configuration
@Configuration
public class PhotoProcessingQueueConfiguration {
    
    public static final String PHOTO_EXCHANGE = "checkin.exchange";
    public static final String PHOTO_QUEUE = "checkin.photos.queue";
    public static final String PHOTO_ROUTING_KEY = "photo.uploaded";
    
    @Bean
    public TopicExchange photoExchange() {
        return new TopicExchange(PHOTO_EXCHANGE, true, false);
    }
    
    @Bean
    public Queue photoQueue() {
        return QueueBuilder.durable(PHOTO_QUEUE)
            .withArgument("x-dead-letter-exchange", "checkin.dlx")
            .withArgument("x-message-ttl", 300000) // 5 minute TTL
            .build();
    }
    
    @Bean
    public Binding photoBinding(Queue photoQueue, TopicExchange photoExchange) {
        return BindingBuilder.bind(photoQueue)
            .to(photoExchange)
            .with(PHOTO_ROUTING_KEY);
    }
    
    // Dead Letter Queue for failed messages
    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange("checkin.dlx", true, false);
    }
    
    @Bean
    public Queue deadLetterQueue() {
        return new Queue("checkin.photos.dlq");
    }
    
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
            .to(deadLetterExchange())
            .with("*");
    }
}
```

---

## Backend Implementation Roadmap

### Sprint 1 (Weeks 1-2): Critical Fixes & Foundations

#### Week 1: Scheduler Optimization & Idempotency

```markdown
## Sprint 1, Week 1 Tasks

### Scheduler Query Optimization (16 story points)
- [ ] Create repository queries for scheduler
  - [ ] findActiveBookingsForCheckInWindow()
  - [ ] findPotentialHostNoShows() with pagination
  - [ ] findPotentialGuestNoShows() with pagination
- [ ] Update CheckInService methods to use queries
- [ ] Create database indexes (V18 migration)
- [ ] Add pagination logic to scheduler
- [ ] Write unit tests for new queries
- [ ] Performance test: 100k bookings < 5 seconds

**Deliverable:** Scheduler can handle 1M+ bookings without OOM

### Idempotency Key Implementation (12 story points)
- [ ] Add idempotency_key column to check_in_events
- [ ] Implement IdempotencyService
- [ ] Add Redis caching for idempotency
- [ ] Update CheckInController with idempotency handling
- [ ] Update CheckOutController with idempotency handling
- [ ] Add Idempotency-Key HTTP header validation
- [ ] Write unit tests for idempotency service
- [ ] Document client-side UUID generation

**Deliverable:** All mutations protected from duplicate execution

### Payment Idempotency (8 story points)
- [ ] Create payment_idempotency table (V18)
- [ ] Implement payment idempotency checks
- [ ] Add tests for payment retry scenarios

**Deliverable:** No duplicate charges even if request retries
```

#### Week 2: Circuit Breaker & Resilience

```markdown
## Sprint 1, Week 2 Tasks

### Circuit Breaker Pattern Implementation (12 story points)
- [ ] Add Resilience4j dependencies
- [ ] Create ResilienceConfiguration bean
- [ ] Implement circuit breaker for EXIF validation
- [ ] Implement circuit breaker for geofence service
- [ ] Implement circuit breaker for payment service
- [ ] Add fallback strategies for each
- [ ] Configuration properties (application-prod.properties)
- [ ] Metrics/health indicators for circuit breakers
- [ ] Unit tests for circuit breaker behavior
- [ ] Integration tests for fallback activation

**Deliverable:** Services degrade gracefully when external dependencies fail

### Retry Strategy with Exponential Backoff (10 story points)
- [ ] Configure RetryRegistry with exponential backoff
- [ ] Implement retry for transient failures
- [ ] Add jitter to prevent thundering herd
- [ ] Configure max retry attempts
- [ ] Log retry attempts with context
- [ ] Tests for retry exhaustion

**Deliverable:** Transient failures automatically retry

### Rate Limiting (10 story points)
- [ ] Add Bucket4j dependency
- [ ] Implement global rate limiter
- [ ] Implement per-user rate limiter
- [ ] Add rate limiting annotations (@RateLimited)
- [ ] Configure limits in properties
- [ ] Return 429 Too Many Requests with Retry-After
- [ ] Tests for rate limiting

**Deliverable:** API protected from abuse/DOS attacks
```

---

### Sprint 2 (Weeks 3-4): Architecture Patterns

#### Week 3: CQRS Implementation

```markdown
## Sprint 2, Week 3 Tasks

### Write Model Refactoring (16 story points)
- [ ] Create CheckInCommandService
- [ ] Move write operations from CheckInService
- [ ] Implement pessimistic locking in commands
- [ ] Add event publishing after writes
- [ ] Create custom exceptions for command validation
- [ ] Unit tests for each command

**Commands to Extract:**
- completeHostCheckIn
- acknowledgeCondition
- confirmHandshake
- processNoShow

### Read Model Implementation (16 story points)
- [ ] Create CheckInStatusView entity
- [ ] Create V19 migration for view table
- [ ] Create CheckInStatusQueryRepository
- [ ] Create CheckInQueryService
- [ ] Implement caching with @Cacheable
- [ ] Write optimized queries for read patterns
- [ ] Unit tests for queries
- [ ] Performance tests: sub-10ms reads

**Queries to Optimize:**
- getCheckInStatus (most frequently called)
- getHostPendingCheckIns
- getGuestPendingCheckIns

### Event-Driven View Synchronization (12 story points)
- [ ] Create CheckIn*Event classes
- [ ] Create event publisher
- [ ] Implement event listeners for view updates
- [ ] Make listeners async (@Async)
- [ ] Add error handling for listener failures
- [ ] Create view rebuild scheduler (hourly)
- [ ] Tests for eventual consistency

**Deliverable:** Separation of read/write concerns, sub-10ms reads
```

#### Week 4: Async Processing & Saga Pattern

```markdown
## Sprint 2, Week 4 Tasks

### Photo Processing Queue (14 story points)
- [ ] Add RabbitMQ/Kafka dependency
- [ ] Create PhotoUploadedEvent
- [ ] Implement message publisher in controller
- [ ] Create PhotoProcessingWorker
- [ ] Implement async EXIF validation
- [ ] Dead letter queue for failures
- [ ] Retry logic with exponential backoff
- [ ] Unit tests for async processing

**Deliverable:** Photo uploads <100ms, validation async

### Checkout Saga Pattern (16 story points)
- [ ] Design checkout saga steps
- [ ] Create CheckoutSaga orchestrator
- [ ] Implement compensation (rollback)
- [ ] Create SagaState persistence
- [ ] Add saga state transitions
- [ ] Implement error handling
- [ ] Add manual intervention alerts
- [ ] Tests for saga flow
- [ ] Tests for saga failure + compensation

**Saga Steps:**
1. Validate photos
2. Calculate late fees
3. Authorize payment
4. Assess damage
5. Create damage claim
6. Complete checkout

**Deliverable:** Atomic multi-service transactions

### Caching Layer (Redis) (12 story points)
- [ ] Add spring-boot-starter-data-redis
- [ ] Configure Redis connection pool
- [ ] Implement cache configuration
- [ ] Add caching to check-in status queries
- [ ] Add cache invalidation on writes
- [ ] Cache warmup strategy
- [ ] Tests for cache hit/miss
- [ ] Metrics for cache performance

**Deliverable:** <50ms response time for cached queries
```

---

## Frontend Implementation Roadmap

### Sprint 3 (Weeks 5-6): Frontend Completion & PWA

#### Week 5: Offline Support & Optimistic Updates

```typescript
// ============================================================================
// OFFLINE SUPPORT: Service Worker for offline-first PWA
// ============================================================================

/**
 * Service Worker: Handles offline functionality
 * - Caches check-in data
 * - Queues mutations when offline
 * - Syncs when connection restored
 */

// sw.ts
import { precacheAndRoute } from 'workbox-precaching';
import { registerRoute } from 'workbox-routing';
import { CacheFirst, NetworkFirst } from 'workbox-strategies';
import { ExpirationPlugin } from 'workbox-expiration';
import { BackgroundSyncPlugin } from 'workbox-background-sync';

declare const self: ServiceWorkerGlobalScope;

// Precache static assets
precacheAndRoute(self.__WB_MANIFEST);

// Network-first for check-in status (get fresh data when online)
registerRoute(
  ({ url }) => url.pathname.includes('/check-in/status'),
  new NetworkFirst({
    cacheName: 'checkin-status',
    plugins: [
      new ExpirationPlugin({
        maxEntries: 100,
        maxAgeSeconds: 5 * 60, // 5 minutes
      }),
    ],
  })
);

// Cache-first for photo uploads (fast offline access)
registerRoute(
  ({ url }) => url.pathname.includes('/check-in/host/photos'),
  new CacheFirst({
    cacheName: 'checkin-photos',
    plugins: [
      new ExpirationPlugin({
        maxEntries: 500,
        maxAgeSeconds: 30 * 24 * 60 * 60, // 30 days
      }),
      new BackgroundSyncPlugin('photo-upload-queue', {
        maxRetentionTime: 24 * 60, // 24 hours
      }),
    ],
  })
);

// Background sync: Queue mutations and retry when online
self.addEventListener('sync', (event: SyncEvent) => {
  if (event.tag === 'photo-upload-queue') {
    event.waitUntil(syncPhotoUploads());
  }
  if (event.tag === 'complete-checkin-queue') {
    event.waitUntil(syncCheckInCompletion());
  }
});

async function syncPhotoUploads(): Promise<void> {
  const db = await openDB('checkin-queue');
  const pendingPhotos = await db.getAll('pending-photos');
  
  for (const photo of pendingPhotos) {
    try {
      await fetch(photo.url, {
        method: photo.method,
        headers: photo.headers,
        body: photo.body,
      });
      await db.delete('pending-photos', photo.id);
    } catch (error) {
      console.error('Failed to sync photo', error);
    }
  }
}

async function syncCheckInCompletion(): Promise<void> {
  const db = await openDB('checkin-queue');
  const pendingSubmissions = await db.getAll('pending-submissions');
  
  for (const submission of pendingSubmissions) {
    try {
      await fetch(submission.url, {
        method: 'POST',
        headers: submission.headers,
        body: JSON.stringify(submission.body),
      });
      await db.delete('pending-submissions', submission.id);
    } catch (error) {
      console.error('Failed to sync submission', error);
    }
  }
}

// ============================================================================
// OFFLINE STORAGE: IndexedDB for client-side state
// ============================================================================

@Injectable({ providedIn: 'root' })
export class OfflineStorageService {
  
  private dbPromise: Promise<IDBDatabase>;
  
  constructor() {
    this.dbPromise = this.initializeDB();
  }
  
  private async initializeDB(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open('rentoza-checkin', 1);
      
      request.onerror = () => reject(request.error);
      request.onsuccess = () => resolve(request.result);
      
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        
        // Store for pending photo uploads
        if (!db.objectStoreNames.contains('pending-photos')) {
          const photoStore = db.createObjectStore('pending-photos', 
            { keyPath: 'id', autoIncrement: true });
          photoStore.createIndex('bookingId', 'bookingId');
          photoStore.createIndex('status', 'status');
        }
        
        // Store for pending check-in submissions
        if (!db.objectStoreNames.contains('pending-submissions')) {
          const submissionStore = db.createObjectStore('pending-submissions',
            { keyPath: 'id', autoIncrement: true });
          submissionStore.createIndex('bookingId', 'bookingId');
        }
        
        // Store for cached check-in status
        if (!db.objectStoreNames.contains('checkin-cache')) {
          const cacheStore = db.createObjectStore('checkin-cache',
            { keyPath: 'bookingId' });
          cacheStore.createIndex('updatedAt', 'updatedAt');
        }
      };
    });
  }
  
  /**
   * Store pending photo upload for later sync.
   * Used when offline.
   */
  async savePendingPhoto(
    bookingId: number,
    photoType: CheckInPhotoType,
    file: File
  ): Promise<number> {
    const db = await this.dbPromise;
    const tx = db.transaction('pending-photos', 'readwrite');
    
    const photoData = {
      bookingId,
      photoType,
      fileName: file.name,
      fileSize: file.size,
      fileData: await this.fileToBase64(file),
      status: 'PENDING',
      createdAt: new Date(),
    };
    
    return new Promise((resolve, reject) => {
      const request = tx.objectStore('pending-photos').add(photoData);
      request.onsuccess = () => resolve(request.result as number);
      request.onerror = () => reject(request.error);
    });
  }
  
  /**
   * Retrieve pending photos for sync.
   */
  async getPendingPhotos(bookingId: number): Promise<any[]> {
    const db = await this.dbPromise;
    const tx = db.transaction('pending-photos', 'readonly');
    const index = tx.objectStore('pending-photos').index('bookingId');
    
    return new Promise((resolve, reject) => {
      const request = index.getAll(bookingId);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  
  private async fileToBase64(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = () => reject(reader.error);
      reader.readAsArrayBuffer(file);
    });
  }
}

// ============================================================================
// OPTIMISTIC UPDATES: Update UI immediately, sync in background
// ============================================================================

@Injectable({ providedIn: 'root' })
export class CheckInOptimisticService {
  
  private readonly status$ = new BehaviorSubject<CheckInStatusDTO | null>(null);
  
  constructor(
    private http: HttpClient,
    private offlineStorage: OfflineStorageService,
    private notificationService: MatSnackBar
  ) {}
  
  /**
   * Complete host check-in with optimistic update.
   * 1. Update local state immediately
   * 2. Submit to backend asynchronously
   * 3. Rollback on failure
   */
  completeHostCheckInOptimistic(
    bookingId: number,
    submission: HostCheckInSubmissionDTO
  ): Observable<CheckInStatusDTO> {
    
    const currentStatus = this.status$.value;
    if (!currentStatus) {
      throw new Error('No check-in status loaded');
    }
    
    // Step 1: Create optimistic state
    const optimisticStatus: CheckInStatusDTO = {
      ...currentStatus,
      status: BookingStatus.CHECK_IN_HOST_COMPLETE,
      hostCheckInComplete: true,
      hostCompletedAt: new Date(),
      odometerReading: submission.odometerReading,
      fuelLevelPercent: submission.fuelLevelPercent,
    };
    
    // Step 2: Update UI immediately (optimistic)
    this.status$.next(optimisticStatus);
    this.notificationService.open('Submitting check-in...', 'CLOSE', {
      duration: 3000,
    });
    
    // Step 3: Submit to backend asynchronously
    return this.http.post<CheckInStatusDTO>(
      `/api/bookings/${bookingId}/check-in/host/complete`,
      submission
    ).pipe(
      // Success: backend returned authoritative state
      tap((serverStatus) => {
        this.status$.next(serverStatus);
        this.notificationService.open(
          'Check-in submitted successfully!', 'CLOSE', { duration: 5000 }
        );
      }),
      // Failure: rollback to previous state
      catchError((error) => {
        this.status$.next(currentStatus); // Rollback
        this.notificationService.open(
          'Failed to submit check-in. Please try again.',
          'RETRY',
          { duration: 0 } // Stay visible until dismissed
        );
        throw error;
      }),
      // Timeout after 30 seconds, retry in background
      timeout(30_000),
      retry({
        count: 3,
        delay: (error, retryCount) => {
          const delayMs = Math.pow(2, retryCount) * 1000; // Exponential backoff
          return timer(delayMs);
        },
      })
    );
  }
  
  /**
   * Handle offline photo uploads.
   * Store locally and sync when online.
   */
  async uploadPhotoOptimistic(
    bookingId: number,
    photoType: CheckInPhotoType,
    file: File
  ): Promise<void> {
    
    // Store file locally (offline)
    const pendingId = await this.offlineStorage.savePendingPhoto(
      bookingId,
      photoType,
      file
    );
    
    // Show optimistic UI
    this.notificationService.open(
      `Photo saved (offline). Will sync when online.`,
      'OK',
      { duration: 3000 }
    );
    
    // Try to sync immediately if online
    if (navigator.onLine) {
      this.syncPendingPhotos(bookingId).catch((error) => {
        console.error('Failed to sync photos:', error);
      });
    }
  }
  
  private async syncPendingPhotos(bookingId: number): Promise<void> {
    const pendingPhotos = await this.offlineStorage.getPendingPhotos(bookingId);
    
    for (const pending of pendingPhotos) {
      try {
        const formData = new FormData();
        // Reconstruct file from base64
        const file = this.base64ToFile(pending.fileData, pending.fileName);
        
        formData.append('file', file);
        formData.append('photoType', pending.photoType);
        
        await this.http.post(
          `/api/bookings/${bookingId}/check-in/host/photos`,
          formData
        ).toPromise();
        
        // Remove from pending
        await this.offlineStorage.markPhotoSynced(pending.id);
      } catch (error) {
        console.error(`Failed to sync photo ${pending.id}:`, error);
      }
    }
  }
  
  private base64ToFile(base64: string, fileName: string): File {
    const arr = base64.split(',');
    const mime = arr[0].match(/:(.*?);/)?.[1] || 'application/octet-stream';
    const bstr = atob(arr[1]);
    const n = bstr.length;
    const u8arr = new Uint8Array(n);
    
    for (let i = 0; i < n; i++) {
      u8arr[i] = bstr.charCodeAt(i);
    }
    
    return new File([u8arr], fileName, { type: mime });
  }
}
```

#### Week 6: E2E Tests & Contract Tests

```typescript
// ============================================================================
// E2E TESTS: Complete check-in flows with Playwright
// ============================================================================

import { test, expect } from '@playwright/test';

test.describe('Check-In Workflow E2E', () => {
  
  let hostPage: Page;
  let guestPage: Page;
  
  test.beforeEach(async ({ browser }) => {
    const context1 = await browser.newContext();
    const context2 = await browser.newContext();
    hostPage = await context1.newPage();
    guestPage = await context2.newPage();
  });
  
  test('complete check-in flow: host upload → guest acknowledge → handshake', async () => {
    // Step 1: Host logs in and navigates to check-in
    await hostPage.goto('http://localhost:4200/bookings/123/check-in');
    await hostPage.fill('[data-test=username]', 'host@example.com');
    await hostPage.fill('[data-test=password]', 'password123');
    await hostPage.click('[data-test=login-button]');
    
    // Wait for check-in wizard
    await hostPage.waitForSelector('[data-test=check-in-wizard]');
    
    // Step 2: Host uploads photos
    await hostPage.click('[data-test=upload-exterior-front]');
    await hostPage.setInputFiles('[data-test=file-input]', 'fixtures/car-front.jpg');
    await hostPage.waitForSelector('[data-test=photo-exterior-front-success]');
    
    // Upload remaining 7 photos...
    for (const type of ['exterior-rear', 'exterior-left', 'exterior-right',
                        'interior-dashboard', 'interior-rear', 'odometer', 'fuel']) {
      await hostPage.click(`[data-test=upload-${type}]`);
      await hostPage.setInputFiles('[data-test=file-input]', `fixtures/car-${type}.jpg`);
      await hostPage.waitForSelector(`[data-test=photo-${type}-success]`);
    }
    
    // Step 3: Host enters readings
    await hostPage.fill('[data-test=odometer]', '45000');
    await hostPage.fill('[data-test=fuel]', '80');
    await hostPage.fill('[data-test=lockbox-code]', '1234');
    
    // Step 4: Host submits
    const submitPromise = hostPage.waitForResponse(response =>
      response.url().includes('/check-in/host/complete') && response.status() === 200
    );
    
    await hostPage.click('[data-test=submit-host-button]');
    await submitPromise;
    
    // Step 5: Verify host sees waiting screen
    await hostPage.waitForSelector('[data-test=check-in-waiting]');
    await expect(hostPage.locator('[data-test=waiting-message]'))
      .toContainText('Čeka se pregled gosta');
    
    // Step 6: Guest logs in and sees photos
    await guestPage.goto('http://localhost:4200/bookings/123/check-in');
    await guestPage.fill('[data-test=username]', 'guest@example.com');
    await guestPage.fill('[data-test=password]', 'password123');
    await guestPage.click('[data-test=login-button]');
    
    // Step 7: Guest reviews photos
    await guestPage.waitForSelector('[data-test=photo-gallery]');
    const photos = await guestPage.$$('[data-test=photo-item]');
    expect(photos.length).toBeGreaterThanOrEqual(8);
    
    // Step 8: Guest marks hotspot (damage)
    await guestPage.click('[data-test=vehicle-wireframe]');
    await guestPage.click('[data-test=hotspot-front-bumper]');
    await guestPage.fill('[data-test=hotspot-description]', 'Small dent in bumper');
    
    // Step 9: Guest accepts condition
    await guestPage.check('[data-test=condition-accepted]');
    
    // Step 10: Guest submits
    const guestSubmitPromise = guestPage.waitForResponse(response =>
      response.url().includes('/check-in/guest/condition-ack') && response.status() === 200
    );
    
    await guestPage.click('[data-test=submit-guest-button]');
    await guestSubmitPromise;
    
    // Step 11: Both parties do handshake
    // Host confirms
    await hostPage.click('[data-test=confirm-handshake-host]');
    
    // Guest confirms with GPS
    await guestPage.click('[data-test=confirm-handshake-guest]');
    // Grant location permission
    await guestPage.context().grantPermissions(['geolocation']);
    
    // Step 12: Verify trip started
    const responsePromise = guestPage.waitForResponse(response =>
      response.url().includes('/check-in/handshake') && response.status() === 200
    );
    
    await responsePromise;
    
    await hostPage.waitForSelector('[data-test=check-in-complete]');
    await guestPage.waitForSelector('[data-test=check-in-complete]');
    
    await expect(hostPage.locator('[data-test=trip-status]'))
      .toContainText('Putovanje je započelo');
  });
  
  test('geofence validation blocks guest too far away', async () => {
    // Setup: Host completed check-in, guest ready to handshake
    // ... setup code ...
    
    // Guest tries to handshake from 5km away
    await guestPage.context().grantPermissions(['geolocation']);
    await guestPage.context().setGeolocation({
      latitude: 44.8176 + 0.05, // ~5km north
      longitude: 20.4557,
      accuracy: 50,
    });
    
    // Click handshake confirm
    const responsePromise = guestPage.waitForResponse(response =>
      response.url().includes('/check-in/handshake')
    );
    
    await guestPage.click('[data-test=confirm-handshake-guest]');
    const response = await responsePromise;
    
    // Should get 403 Forbidden
    expect(response.status()).toBe(403);
    
    // Guest sees error message
    await guestPage.waitForSelector('[data-test=geofence-error]');
    await expect(guestPage.locator('[data-test=geofence-error]'))
      .toContainText('Privi ste daleko od vozila');
  });
  
  test('photo compression works on slow network', async () => {
    // Setup: Simulate slow 3G network
    const context = hostPage.context();
    await context.setOffline(false);
    // Simulate slow network via Chrome DevTools Protocol
    
    // Upload large photo
    await hostPage.click('[data-test=upload-exterior-front]');
    const largeFile = 'fixtures/car-large-5mb.jpg';
    
    // Monitor network request
    const uploadPromise = hostPage.waitForResponse(response =>
      response.url().includes('/check-in/host/photos')
    );
    
    await hostPage.setInputFiles('[data-test=file-input]', largeFile);
    const response = await uploadPromise;
    
    // File should be compressed to <500KB
    const request = response.request();
    const postData = request.postDataJSON();
    
    expect(postData.fileSize).toBeLessThan(512_000); // 500KB
  });
});

// ============================================================================
// CONTRACT TESTS: API contract verification
// ============================================================================

import { PactV3 } from '@pact-foundation/pact';

const provider = new PactV3({
  consumer: 'rentoza-frontend',
  provider: 'rentoza-backend',
});

describe('Check-In API Contract', () => {
  
  test('GET /check-in/status returns correct format', async () => {
    await provider
      .given('a booking in CHECK_IN_OPEN state')
      .uponReceiving('a request for check-in status')
      .withRequest('GET', '/api/bookings/123/check-in/status')
      .willRespondWith(200, (body) => {
        // Define expected response structure
        expect(body).toEqual({
          bookingId: 123,
          checkInSessionId: expect.any(String),
          status: 'CHECK_IN_OPEN',
          hostCheckInComplete: expect.any(Boolean),
          guestCheckInComplete: expect.any(Boolean),
          vehiclePhotos: expect.arrayContaining([
            {
              photoId: expect.any(Number),
              photoType: expect.any(String),
              url: expect.any(String),
              exifValidationStatus: expect.stringMatching(/^(VALID|REJECTED|VALID_WITH_WARNINGS)$/),
            },
          ]),
          odometerReading: expect.any(Number),
          fuelLevelPercent: expect.any(Number),
          car: {
            id: expect.any(Number),
            brand: expect.any(String),
            model: expect.any(String),
          },
        });
      });
  });
  
  test('POST /host/complete accepts valid submission', async () => {
    await provider
      .given('a booking in CHECK_IN_OPEN state with 8 photos')
      .uponReceiving('a host submission')
      .withRequest('POST', '/api/bookings/123/check-in/host/complete', {
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer jwt_token',
        },
        body: {
          odometerReading: 45000,
          fuelLevelPercent: 80,
          lockboxCode: '1234',
        },
      })
      .willRespondWith(200, {
        body: {
          status: 'CHECK_IN_HOST_COMPLETE',
          hostCheckInComplete: true,
        },
      });
  });
});
```

---

## Testing & Quality Assurance

### Unit Test Coverage Targets

```markdown
## Required Test Coverage

### Backend: 85% minimum coverage

| Module | Current | Target | Tests Needed |
|--------|---------|--------|-------------|
| CheckInService | 40% | 90% | +50 tests |
| CheckInPhotoService | 30% | 85% | +55 tests |
| ExifValidationService | 50% | 90% | +40 tests |
| GeofenceService | 60% | 90% | +30 tests |
| CheckOutService | 10% | 85% | +75 tests |
| IdVerificationService | 5% | 85% | +80 tests |
| DamageClaimService | 5% | 85% | +80 tests |

**Total Tests to Add: ~410 unit tests (Effort: 60 story points)**

### Frontend: 75% minimum coverage

| Component | Current | Target | Tests Needed |
|-----------|---------|--------|-------------|
| CheckInWizardComponent | 50% | 85% | +35 tests |
| HostCheckInComponent | 40% | 85% | +45 tests |
| GuestCheckInComponent | 30% | 85% | +55 tests |
| HandshakeComponent | 30% | 85% | +55 tests |
| CheckInService (RxJS) | 35% | 85% | +50 tests |

**Total Tests to Add: ~240 tests (Effort: 40 story points)**
```

### Integration Test Scenarios

```java
/**
 * Integration tests: Verify end-to-end workflows
 * Runs against actual database and external services (or mocks)
 */

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestcontainersTest
class CheckInIntegrationTests {
    
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private BookingRepository bookingRepository;
    @Container private static PostgreSQLContainer<?> postgres = 
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"));
    
    @Test
    void shouldCompleteFullCheckInWorkflow() {
        // Create test booking
        Booking booking = createTestBooking(BookingStatus.CHECK_IN_OPEN);
        bookingRepository.save(booking);
        
        // Host submits check-in
        HostCheckInSubmissionDTO hostSubmission = new HostCheckInSubmissionDTO();
        hostSubmission.setBookingId(booking.getId());
        hostSubmission.setOdometerReading(45000);
        hostSubmission.setFuelLevelPercent(80);
        
        ResponseEntity<CheckInStatusDTO> hostResponse = restTemplate
            .withBasicAuth("host@example.com", "password")
            .postForEntity(
                "/api/bookings/{id}/check-in/host/complete",
                hostSubmission,
                CheckInStatusDTO.class,
                booking.getId()
            );
        
        assertThat(hostResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hostResponse.getBody().getStatus())
            .isEqualTo(BookingStatus.CHECK_IN_HOST_COMPLETE);
        
        // Guest acknowledges condition
        GuestConditionAcknowledgmentDTO guestAck = 
            new GuestConditionAcknowledgmentDTO();
        guestAck.setBookingId(booking.getId());
        guestAck.setConditionAccepted(true);
        
        ResponseEntity<CheckInStatusDTO> guestResponse = restTemplate
            .withBasicAuth("guest@example.com", "password")
            .postForEntity(
                "/api/bookings/{id}/check-in/guest/condition-ack",
                guestAck,
                CheckInStatusDTO.class,
                booking.getId()
            );
        
        assertThat(guestResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(guestResponse.getBody().getStatus())
            .isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
        
        // Both parties confirm handshake
        HandshakeConfirmationDTO hostConfirm = new HandshakeConfirmationDTO();
        hostConfirm.setBookingId(booking.getId());
        hostConfirm.setConfirmed(true);
        
        restTemplate
            .withBasicAuth("host@example.com", "password")
            .postForEntity(
                "/api/bookings/{id}/check-in/handshake",
                hostConfirm,
                CheckInStatusDTO.class,
                booking.getId()
            );
        
        HandshakeConfirmationDTO guestConfirm = new HandshakeConfirmationDTO();
        guestConfirm.setBookingId(booking.getId());
        guestConfirm.setConfirmed(true);
        guestConfirm.setLatitude(44.8176);
        guestConfirm.setLongitude(20.4557);
        
        ResponseEntity<CheckInStatusDTO> finalResponse = restTemplate
            .withBasicAuth("guest@example.com", "password")
            .postForEntity(
                "/api/bookings/{id}/check-in/handshake",
                guestConfirm,
                CheckInStatusDTO.class,
                booking.getId()
            );
        
        // Verify trip started
        assertThat(finalResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalResponse.getBody().getStatus())
            .isEqualTo(BookingStatus.IN_TRIP);
    }
    
    @Test
    void shouldRejectGeofenceViolation() {
        // Setup: Host completed, guest trying to handshake from 5km away
        
        HandshakeConfirmationDTO guestConfirm = new HandshakeConfirmationDTO();
        guestConfirm.setConfirmed(true);
        guestConfirm.setLatitude(44.8676); // 5km away
        guestConfirm.setLongitude(20.4557);
        
        ResponseEntity<CheckInStatusDTO> response = restTemplate
            .withBasicAuth("guest@example.com", "password")
            .postForEntity(
                "/api/bookings/{id}/check-in/handshake",
                guestConfirm,
                CheckInStatusDTO.class,
                bookingId
            );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

---

## Observability & Operations

### Distributed Tracing

```java
// Add Micrometer Tracing
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-spring-cloud-sleuth</artifactId>
</dependency>

// Configuration
@Configuration
public class TracingConfiguration {
    
    @Bean
    public Sampler sampler() {
        return Sampler.ALWAYS_SAMPLE; // Production: use percentage-based
    }
}

// Usage in service
@Service
@Slf4j
public class CheckInService {
    
    @Transactional
    public void completeHostCheckIn(HostCheckInSubmissionDTO dto, Long userId) {
        // Micrometer automatically captures this method call
        // with trace ID spanning across services
        log.info("[CheckIn] Completing host check-in");
        // ...
    }
}
```

### Advanced Metrics

```java
@Service
@Slf4j
public class MetricsService {
    
    private final MeterRegistry meterRegistry;
    
    /**
     * Comprehensive metrics for observability
     */
    public void recordCheckInMetrics(Booking booking, Duration duration) {
        // Latency timer
        meterRegistry.timer("checkin.duration.ms")
            .record(duration);
        
        // Success counter
        meterRegistry.counter("checkin.success")
            .increment();
        
        // Gauge: Current pending check-ins
        meterRegistry.gauge("checkin.pending",
            bookingRepository.countByStatus(BookingStatus.CHECK_IN_OPEN));
        
        // Distribution: Check-in duration percentiles
        meterRegistry.timer("checkin.duration.percentiles",
            "percentile", "p50")
            .record(duration);
    }
}
```

---

## DevOps & Deployment

### Blue-Green Deployment Strategy

```yaml
# Kubernetes deployment: blue-green strategy
---
apiVersion: v1
kind: Service
metadata:
  name: rentoza-checkin-blue
spec:
  selector:
    app: rentoza-checkin
    version: blue
  ports:
    - port: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: rentoza-checkin-green
spec:
  selector:
    app: rentoza-checkin
    version: green
  ports:
    - port: 8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rentoza-checkin-blue
spec:
  replicas: 3
  selector:
    matchLabels:
      app: rentoza-checkin
      version: blue
  template:
    metadata:
      labels:
        app: rentoza-checkin
        version: blue
    spec:
      containers:
        - name: rentoza-checkin
          image: rentoza-checkin:v1.2.0
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: prod
            - name: DATABASE_REPLICA
              value: "true"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 5
          resources:
            requests:
              cpu: "500m"
              memory: "512Mi"
            limits:
              cpu: "2"
              memory: "2Gi"

# Ingress canary: 90% blue, 10% green during rollout
---
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: rentoza-checkin
spec:
  hosts:
    - api.rentoza.com
  http:
    - match:
        - uri:
            prefix: /api/bookings
      route:
        - destination:
            host: rentoza-checkin-blue
            port:
              number: 8080
          weight: 90
        - destination:
            host: rentoza-checkin-green
            port:
              number: 8080
          weight: 10
```

---

## Summary: Implementation Priorities

### Immediate (Weeks 1-2): Must-Do
1. ✅ Scheduler query optimization (O(n) → O(log n))
2. ✅ Idempotency keys for all mutations
3. ✅ Circuit breaker pattern

### High Priority (Weeks 3-4): Should-Do
4. ✅ CQRS separation (read vs write optimization)
5. ✅ Async photo processing (RabbitMQ)
6. ✅ Checkout saga pattern
7. ✅ Redis caching layer

### Medium Priority (Weeks 5-6): Nice-To-Have
8. ✅ Offline support (Service Worker)
9. ✅ Optimistic updates (UX improvement)
10. ✅ Comprehensive E2E tests
11. ✅ Contract testing

### Polish (Weeks 7-8): Final Touches
12. ✅ Distributed tracing (observability)
13. ✅ Advanced monitoring dashboards
14. ✅ Security audit & penetration testing
15. ✅ Performance tuning & load testing

---

**This plan positions Rentoza Check-In at enterprise grade (Turo/Airbnb production level) within 8 weeks.**

**Total Effort: 320-400 story points (4-5 senior engineers for 8 weeks)**

---

## Implementation Progress Log

### Phase 1: Core Resilience (COMPLETED ✅)
**Implemented:** June 2025

| Item | Status | File(s) Created/Modified |
|------|--------|--------------------------|
| Scheduler Optimization (Idempotency) | ✅ Complete | `CheckInIdempotencyService.java`, `CheckInIdempotencyRecord.java`, `CheckInIdempotencyRepository.java`, `V18__check_in_idempotency.sql` |
| Circuit Breaker Pattern | ✅ Complete | `Resilience4jConfig.java`, `CircuitBreakerHealthIndicator.java` |
| Rate Limiting | ✅ Complete | `RateLimitConfig.java`, `RateLimitInterceptor.java` |
| Retry with Backoff | ✅ Complete | Integrated in `Resilience4jConfig.java` |

**Key Dependencies Added:**
- `resilience4j-spring-boot3:2.2.0`
- `resilience4j-micrometer:2.2.0`

### Phase 2: Data Architecture & Async Processing (COMPLETED ✅)
**Implemented:** June 2025

| Item | Status | File(s) Created/Modified |
|------|--------|--------------------------|
| CQRS Command Service | ✅ Complete | `booking/checkin/cqrs/CheckInCommandService.java` |
| CQRS Query Service | ✅ Complete | `booking/checkin/cqrs/CheckInQueryService.java` |
| CheckInStatusView Entity | ✅ Complete | `booking/checkin/cqrs/CheckInStatusView.java`, `CheckInStatusViewRepository.java` |
| Domain Events | ✅ Complete | `booking/checkin/cqrs/CheckInDomainEvent.java` |
| View Sync Listener | ✅ Complete | `booking/checkin/cqrs/CheckInStatusViewSyncListener.java` |
| Redis Cache Configuration | ✅ Complete | `config/RedisCacheConfig.java` |
| RabbitMQ Configuration | ✅ Complete | `config/RabbitMQConfig.java` |
| Photo Validation Worker | ✅ Complete | `booking/checkin/photo/PhotoValidationWorker.java`, `PhotoValidationMessage.java`, `PhotoValidationFallback.java` |
| Checkout Saga Orchestrator | ✅ Complete | `booking/checkout/saga/CheckoutSagaOrchestrator.java`, `CheckoutSagaStep.java`, `CheckoutSagaState.java`, `CheckoutSagaStateRepository.java` |
| Saga Recovery Scheduler | ✅ Complete | `booking/checkout/saga/SagaRecoveryScheduler.java` |

**Database Migrations:**
- `V20__cqrs_checkin_status_view.sql` - CQRS read model table
- `V21__checkout_saga_state.sql` - Saga state persistence table

**Key Dependencies Added:**
- `spring-boot-starter-amqp` (RabbitMQ)

**Configuration Added (application-dev.properties):**
```properties
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672
spring.rabbitmq.username=guest
spring.rabbitmq.password=guest
app.rabbitmq.enabled=true
app.checkin.photo.queue=checkin.photos.queue
```

### Phase 3: API & Frontend Optimization (PENDING ⏳)
**Status:** Not Started

| Item | Status | Notes |
|------|--------|-------|
| GraphQL Integration | ⏳ Pending | |
| WebSocket Real-time Updates | ⏳ Pending | |
| Service Worker Offline Support | ⏳ Pending | |
| Optimistic UI Updates | ⏳ Pending | |

### Phase 4: Polish & Production Hardening (PENDING ⏳)
**Status:** Not Started

| Item | Status | Notes |
|------|--------|-------|
| Distributed Tracing | ⏳ Pending | |
| Advanced Monitoring | ⏳ Pending | |
| Security Audit | ⏳ Pending | |
| Performance Tuning | ⏳ Pending | |

---

**Last Updated:** June 2025
**Implementation Lead:** Principal Software Architect
