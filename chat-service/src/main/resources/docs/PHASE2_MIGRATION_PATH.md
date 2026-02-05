# Phase 2 Migration Path: Enhanced Booking Validation

## Current State (Phase 1 - Option B)

**Architecture:** Main Backend Creates Conversations

```
Main Backend                  Chat Service
     |                             |
     | Booking APPROVED            |
     |-----------------------------\u003e
     | POST /api/internal/conversations
     |                          Creates conversation
     |                          (trusts main backend)
     |\u003c-----------------------------
     | 201 Created              |
     |                             |
User |\u003c------------------------------|
     | Can now chat               |
```

**Pros:**
- ✅ Simple chat service (less validation logic)
- ✅ Single source of truth (main backend)
- ✅ No network calls on message send

**Cons:**
- ❌ Tight coupling (main backend knows about chat)
- ❌ Cannot create conversation from chat UI
- ❌ Main backend must remember to call chat service

---

## Target State (Phase 2 - Option A)

**Architecture:** Chat Service Validates Independently

```
User (Frontend)              Chat Service              Main Backend
     |                             |                         |
     | Create conversation         |                         |
     |---------------------------\u003e|                         |
     |                             | Validate booking        |
     |                             |------------------------\u003e
     |                             |   GET /api/bookings/:id
     |                             |                         |
     |                             |\u003c-----------------------|
     |                             | {status: "APPROVED",    |
     |                             |  renterId: 100,         |
     |                             |  ownerId: 200}          |
     |                             |                         |
     |                             | Verify user is participant
     |                             | Create conversation     |
     |\u003c----------------------------|                         |
     | 201 Created                 |                         |
```

**Pros:**
- ✅ Stricter security (chat validates booking)
- ✅ Loose coupling (services independent)
- ✅ User-initiated conversations possible
- ✅ Better tenant isolation

**Cons:**
- ❌ Network latency (~100ms per create)
- ❌ Dependency on main backend availability
- ❌ More complex error handling

---

## Implementation Steps

### Step 1: Add Booking API Client

**File:** `chat-service/src/main/java/org/example/chatservice/client/BookingApiClient.java`

```java
@Component
@RequiredArgsConstructor
public class BookingApiClient {
    
    private final WebClient.Builder webClientBuilder;
    
    @Value("${backend.api.base-url}")
    private String backendApiUrl;
    
    /**
     * Get booking details from main backend
     * 
     * @Cacheable with 15-minute TTL (booking status rarely changes rapidly)
     */
    @Cacheable(value = "bookingStatus", key = "#bookingId", unless = "#result == null")
    @CircuitBreaker(name = "bookingApi", fallbackMethod = "getBookingDetailsFallback")
    public BookingDTO getBookingDetails(String bookingId) {
        return webClientBuilder.build()
                .get()
                .uri(backendApiUrl + "/api/bookings/" + bookingId)
                .retrieve()
                .bodyToMono(BookingDTO.class)
                .timeout(Duration.ofSeconds(2))
                .block();
    }
    
    private BookingDTO getBookingDetailsFallback(String bookingId, Exception ex) {
        log.error("Failed to fetch booking details for {}: {}", bookingId, ex.getMessage());
        throw new ServiceUnavailableException("Booking service temporarily unavailable");
    }
}
```

### Step 2: Add Booking Validation in ChatService

**File:** `chat-service/src/main/java/org/example/chatservice/service/ChatService.java`

```java
@Service
@RequiredArgsConstructor
public class ChatService {
    
    private final BookingApiClient bookingApiClient;  // NEW
    
    @Value("${booking.validation.enabled:false}")  // Feature flag
    private boolean bookingValidationEnabled;
    
    public ConversationDTO createConversationSecure(
            CreateConversationRequest request, 
            String authenticatedUserId) {
        
        // NEW: Validate booking if feature enabled
        if (bookingValidationEnabled) {
            validateBookingApproval(request, authenticatedUserId);
        }
        
        // Existing logic...
        return createConversation(request);
    }
    
    private void validateBookingApproval(
            CreateConversationRequest request, 
            String authenticatedUserId) {
        
        // 1. Fetch booking from main backend
        BookingDTO booking = bookingApiClient.getBookingDetails(request.getBookingId());
        
        // 2. Validate booking status
        if (!"APPROVED".equals(booking.getStatus())) {
            throw new ForbiddenException(
                    "Cannot create conversation for booking with status: " + booking.getStatus());
        }
        
        // 3. Validate authenticated user is participant
        if (!authenticatedUserId.equals(booking.getRenterId()) && 
            !authenticatedUserId.equals(booking.getOwnerId())) {
            throw new ForbiddenException(
                    "User is not a participant in this booking");
        }
        
        // 4. Validate request data matches booking
        if (!request.getRenterId().equals(booking.getRenterId())) {
            throw new BadRequestException("Renter ID mismatch");
        }
        
        if (!request.getOwnerId().equals(booking.getOwnerId())) {
            throw new BadRequestException("Owner ID mismatch");
        }
    }
}
```

### Step 3: Update ChatController

**File:** `chat-service/src/main/java/org/example/chatservice/controller/ChatController.java`

```java
@PostMapping("/conversations")
public ResponseEntity<ConversationDTO> createConversation(
        @Valid @RequestBody CreateConversationRequest request,
        Authentication authentication) {
    
    String authenticatedUserId = authentication.getName();
    
    // NEW: Use secure method with validation
    ConversationDTO conversation = chatService.createConversationSecure(
            request, 
            authenticatedUserId);
    
    return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
}
```

### Step 4: Add Booking Status Cache

**File:** `chat-service/src/main/java/org/example/chatservice/config/CacheConfig.java`

```java
@Bean
public CacheManager bookingCacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager("bookingStatus");
    manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(5_000)  // 5k bookings cached
            .expireAfterWrite(15, TimeUnit.MINUTES)  // 15-minute TTL
            .recordStats()
            .build());
    return manager;
}
```

---

## Rollout Strategy

### Week 1: Deploy with Feature Flag Disabled

```properties
# application.properties
booking.validation.enabled=false
```

- Deploy code to production
- Internal endpoint still works
- Zero impact on existing functionality
- Monitor logs for any issues

### Week 2: Enable for 10% Traffic

```java
@Component
public class BookingValidationConfig {
    
    @Value("${booking.validation.rollout-percentage:0}")
    private int rolloutPercentage;
    
    public boolean shouldValidate() {
        return ThreadLocalRandom.current().nextInt(100) < rolloutPercentage;
    }
}
```

```properties
booking.validation.rollout-percentage=10
```

- Monitor errors, latency, cache hit rate
- Expected: cache hit >80%, latency <150ms p95

### Week 3: Enable for 100%

```properties
booking.validation.enabled=true
booking.validation.rollout-percentage=100
```

- Full validation for all conversation creations
- Monitor for 48 hours
- Be ready to rollback feature flag

### Week 4: Deprecate Internal Endpoint

```java
@Deprecated(forRemoval = true)
@PostMapping("/conversations")
public ResponseEntity<ConversationDTO> createConversationInternal(...) {
    log.warn("DEPRECATED: Internal endpoint called. Migrate to user-initiated conversations.");
    // Still functional but logged as deprecated
}
```

- Main backend no longer calls internal endpoint
- Users create conversations directly from chat UI
- Remove endpoint in Month 2

---

## Metrics to Monitor

### Performance SLOs

| Metric | Target | Action if Exceeded |
|--------|--------|-------------------|
| Booking API latency (p95) | <150ms | Increase cache TTL to 30min |
| Cache hit rate | >80% | Investigate booking status changes |
| Validation failures | <5% | Review booking state transitions |
| Service unavailable errors | <1% | Implement fallback: allow creation, validate async |

### Alerting

```yaml
# Prometheus alerts
- alert: BookingApiHighLatency
  expr: histogram_quantile(0.95, booking_api_duration_seconds) > 0.15
  for: 5m
  annotations:
    summary: "Booking API calls exceeding 150ms p95"
    
- alert: BookingCacheLowHitRate
  expr: cache_hit_rate{cache="bookingStatus"} < 0.80
  for: 10m
  annotations:
    summary: "Booking cache hit rate below 80%"
```

---

## Rollback  Plan

**Trigger Conditions:**
- ❌ Validation failures >10%
- ❌ Latency >500ms p95
- ❌ Cache misses >50%
- ❌ User complaints about conversation creation

**Rollback Steps:**
1. Set `booking.validation.enabled=false`
2. Restart chat service (or hot-reload config if supported)
3. Verify internal endpoint working
4. Investigate root cause (main backend API issues?)
5. Fix and retry rollout

---

## Testing Checklist

- [ ] Unit tests: Validate booking status checks
- [ ] Integration tests: Mock BookingApiClient
- [ ] Load tests: 100 concurrent conversation creations
- [ ] Staging: Test with real Supabase + main backend
- [ ] Security: Try creating conversation for unapproved booking → 403
- [ ] Fallback: Simulate main backend down → graceful error

---

## Success Criteria

✅ All conversation creations validated against main backend  
✅ Cache hit rate >80% after warmup  
✅ No increase in error rate (\u003c1%)  
✅ Latency <150ms p95  
✅ Zero security bypasses (tested via penetration testing)  
✅ Main backend no longer calls internal endpoint  

**Completion Date:** TBD (After Phase 1 stable in production)
