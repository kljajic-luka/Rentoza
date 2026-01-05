# AI Code Agent Directive: Enterprise-Grade Supabase Migration Plan

## Executive Context

**Current State**: Spring Boot (Java 21) + Angular 20 + MySQL 8.4 + chat-service microservice  
**Target State**: Supabase (PostgreSQL + PostGIS) + TypeScript client + edge functions  
**Constraints**: Zero downtime, zero data loss, enterprise-grade practices, no assumptions  
**Deliverable**: Production-ready migration code, detailed execution plan, rollback procedures

---

## Task 1: Comprehensive Codebase Analysis (NO ASSUMPTIONS)

### 1.1 Full Repository Inventory

Analyze `/Users/kljaja01/Developer/Rentoza/Rentoza/src/main/java` and map:

**REQUIRED OUTPUTS:**

```markdown
## Entities & Tables
- List EVERY @Entity with:
  - Table name, column count, estimated rows
  - Primary/foreign keys
  - Unique constraints
  - Indexes (including compound indexes)
  - @Version fields (optimistic locking)
  - @Immutable entities (append-only)
  - Soft delete patterns (deletedAt timestamps)
  - Encrypted columns (AES-256-GCM)
  - Temporal columns (createdAt, updatedAt)
  - Enum types (as @Enumerated or VARCHAR)
  - @ElementCollection fields (JSON arrays/maps)
  - Custom @Converter types

Example output format:
| Table | Columns | PKs | FKs | Indexes | Constraints | Migration Notes |
|-------|---------|-----|-----|---------|-------------|-----------------|
| bookings | 45 | id (BIGINT) | car_id, renter_id | 12 | version (optimistic lock), unique(car_id, start_time, status) | CRITICAL: Pessimistic locking in queries |
| check_in_photos | 20 | id | booking_id | 5 | deletedAt (soft delete), filename (immutable) | MEDIUM: File references to migrate |
```

## Services & Transactions
- Every @Service and @Transactional method
  - Mark transaction scope (REQUIRED, SUPPORTS, REQUIRES_NEW, NEVER)
  - External API calls (payment, verification, OCR)
  - Message queue interactions (RabbitMQ)
  - Cache usage (Redis)
  - Lock acquisitions (@Lock)
  - Saga orchestration patterns
  
Example format:
```
BookingPaymentService.capturePayment()
├─ Scope: REQUIRED (wraps entire payment flow)
├─ External: Stripe API (payment_provider)
├─ Cache: booking.id → Redis (invalidate on update)
├─ Lock: Booking.id with PESSIMISTIC_WRITE (5s timeout)
├─ Failure modes: Provider timeout, payment declined, DB conflict
└─ Recovery: Idempotency key via RabbitMQ (duplicate detection)
```

## Repositories & Query Patterns
- All JPA repositories with method signatures
  - Query type (nativeQuery, JPQL, Specification)
  - JOIN FETCH vs @EntityGraph usage
  - Pagination/sorting
  - Locking mode (PESSIMISTIC_WRITE, PESSIMISTIC_READ, OPTIMISTIC)
  - RLS enforcement (@Query with userId conditions)
  
Example format:
```
BookingRepository
├─ findByCarIdAndTimeRange(carId, startTime, endTime): List<Booking>
│  ├─ Type: JPQL with 3-way JOIN FETCH
│  ├─ Index: idx_booking_car_time (car_id, start_time, end_time)
│  ├─ Migration: DIRECT (interval logic identical in PostgreSQL)
│  └─ Risk: N+1 on nested lazy-loaded collections
├─ findNearby(lat, lon, radiusKm): List<Car> [NATIVE]
│  ├─ Type: Native MySQL spatial query (ST_Distance_Sphere)
│  ├─ Migration: UPGRADE to PostgreSQL PostGIS (ST_DWithin faster)
│  └─ Testing: Compare query plans (MySQL vs PG)
```

## Configuration & Properties
- Parse ALL configuration files:
  - `application.yaml` / `application-prod.yaml`
  - Secrets (API keys, encryption keys, DB credentials)
  - Feature flags
  - Cache TTLs
  - Transaction timeouts
  - Connection pool settings
  - CORS configuration
  - Email/SMS provider config
  
Output as table:
```
| Config Key | Current Value | Type | Migration Impact |
|------------|---------------|------|------------------|
| spring.jpa.hibernate.ddl-auto | validate | CRITICAL | Change to 'none' (Flyway manages schema) |
| jwt.secret | (from env) | SECRET | Migrate to AWS Secrets Manager |
| redis.ttl.verification | 3600 | MEDIUM | Supabase Functions handle (no Redis needed) |
```

## Third-Party Integrations
- Payment providers (Stripe mock, Monri, wspay)
- Email services (SMTP, Mailgun, SendGrid)
- SMS providers (Twilio, local SMS gateway)
- OCR services (async webhooks)
- Liveness detection (face matching API)
- Image processing (EXIF stripping, compression)
- Map services (geolocation, OSRM routing)
- Push notifications (Firebase)
- Chat service (microservice communication)

Output format:
```
| Integration | Endpoint | Auth Method | Failure Mode | Migration |
|-------------|----------|-------------|--------------|-----------|
| Stripe | /v1/charges | Bearer token | Timeout → retry | Unchanged (keep in Edge Function) |
| Firebase | FCM API | Service account | Network error | Supabase Functions wrapper |
| OSRM | /route/v1 | API key | Rate limit 429 | Wrap in Edge Function |
```

---

### 1.2 Frontend Codebase Deep Dive

Analyze `/Users/kljaja01/Developer/Rentoza/rentoza-frontend/src`:

**REQUIRED OUTPUTS:**

```markdown
## Angular Components & Services
- Every HTTP service (@Injectable)
  - API endpoints called
  - Authentication method (JWT token, OAuth)
  - Request/response DTOs
  - Error handling (retry logic, timeout)
  - Caching strategy (RxJS operators)
  - Real-time subscriptions (WebSocket, polling)

## State Management
- RxJS Subjects/BehaviorSubjects
  - Which components subscribe
  - Cache invalidation triggers
  - Memory leak risks

## Interceptors & Guards
- HTTP interceptors (JWT injection, error handling)
- Route guards (canActivate, canDeactivate)
- Auth flow (login → token → session)

## Feature Modules & Lazy Loading
- Module dependencies
- Bundle size impact
- Code splitting opportunities

## API Calls (Inventory)
- Catalog all HTTP requests (GET, POST, PUT, DELETE)
- Group by feature (booking, verification, chat, etc.)
- Mark which are real-time candidates (WebSocket → Supabase)
```

---

### 1.3 Chat Service Microservice Analysis

Analyze `/Users/kljaja01/Developer/Rentoza/chat-service/src`:

**REQUIRED OUTPUTS:**

```markdown
## Chat Service Architecture
- REST endpoints exposed
- Database schema (Conversation, Message, MessageReadReceipt)
- WebSocket/STOMP integration
- Message ordering guarantees
- Read receipt tracking

## Integration Points
- How main Spring Boot service communicates (REST? gRPC? messaging?)
- Authentication/authorization (JWT validation)
- Data sync mechanisms

## Real-Time Requirements
- Ordering: Timestamp-based? Lamport clock?
- Delivery guarantees: At-least-once? Exactly-once?
- Presence tracking: Who's online?
- Typing indicators: If implemented?

## Migration Path
- Supabase Realtime (native subscriptions)
- Message ordering via created_at + id (tie-breaker)
- Read receipts as separate Realtime stream
- Archive strategy (monthly cold storage)
```

---

## Task 2: Build Master Migration Roadmap

### 2.1 Dependency Graph (Data & Behavioral)

Create a directed graph showing:

```
User (id) ──→ Booking (user_id) ──→ Car (car_id) ──→ CarDocument
         ├────→ RenterDocument
         ├────→ Notification
         └────→ RefreshToken

Booking ──→ CheckInPhoto (booking_id)
        ├─→ CheckInEvent
        ├─→ CheckInStatusView (CQRS)
        └─→ CheckoutSagaState

CheckInPhoto ──→ S3/filesystem (file reference)
```

For each edge, document:
- Cascading deletes (how to handle in PG)
- Cascade save (updates propagate)
- RLS boundaries (who can see related data)
- Temporal constraints (data can only be created/read within date ranges)

### 2.2 Data Pipeline Design

```
STAGE 1: MySQL Snapshot (Week 0, Day 1)
├─ mysqldump --single-transaction (no locks)
├─ Compress + checksum (MD5, SHA256)
└─ Archive for rollback

STAGE 2: PostgreSQL Schema Creation (Week 1)
├─ Flyway migrations 1-40 re-run on PostgreSQL
├─ Create RLS policies (28 tables × N policies)
├─ Create indexes (ensure compound index order preserved)
└─ Create triggers (immutable entities, event sourcing)

STAGE 3: Data Import (Week 1)
├─ pg_restore from mysqldump → PostgreSQL
├─ Validate row counts (must match MySQL exactly)
├─ Check foreign key integrity
├─ Verify no constraint violations
├─ Compare checksums (table-level, column-level)
└─ Archive PostgreSQL backup (for rollback)

STAGE 4: Dual-Write Validation (Week 1, Days 5-7)
├─ Enable dual-write: writes go to MySQL + PostgreSQL
├─ Run consistency checks every 1 hour (compare snapshots)
├─ Monitor for divergence
├─ Capture any missed records (reconcile)
└─ Decision: Proceed or rollback

STAGE 5: Read Cutover (Week 2, Days 1-2)
├─ Enable feature flag: READ_FROM_SUPABASE
├─ Gradual rollout: 5% → 25% → 50% → 100%
├─ Monitor latency: P50, P95, P99
├─ Compare query results (spot checks)
└─ If success, disable dual-read from MySQL

STAGE 6: Write Cutover (Week 2, Days 3-7)
├─ Maintain dual-write for 7 more days
├─ Enable feature flag: WRITE_TO_SUPABASE
├─ Validate all operations (create, update, delete)
├─ Audit logs confirm writes landed
└─ Eventually consistent check

STAGE 7: Decommission MySQL (Week 3)
├─ Keep read replica running (48 hours)
├─ Document decommission date
├─ Archive MySQL backup to cold storage
└─ Update runbooks
```

### 2.3 RLS Policy Matrix (All 28 Tables)

Create a spreadsheet-style output:

```
| Table | Read Policy | Write Policy | Delete Policy | Notes |
|-------|------------|--------------|---------------|-------|
| users | (auth.uid() = id) | self-only | admin-only | PII: JMBG, PIB encrypted |
| bookings | (auth.uid() = renter_id OR car_owner = auth.uid()) | renter/owner | disputed → admin | Time-based access (only during trip) |
| cars | (available = true) OR (owner = auth.uid()) | owner-only | owner/admin | Location data public |
| check_in_photos | (booking.renter = auth.uid() OR booking.owner = auth.uid()) | append-only | admin | Immutable (soft delete only) |
| admin_audit_log | admin-only | append-only | never | Immutable, never deleted |
```

---

## Task 3: Detailed Migration Code (Production Quality)

### 3.1 TypeScript Supabase Client Library

**OUTPUT REQUIREMENT**: Create complete, production-ready library with:

```typescript
// file: supabase-client/index.ts
export * from './auth'
export * from './bookings'
export * from './cars'
export * from './users'
export * from './notifications'
export * from './realtime'
export { createSupabaseClient } from './client'

// file: supabase-client/client.ts
import { createClient, SupabaseClient } from '@supabase/supabase-js'
import { Database } from './database.types'  // Generated from schema

export function createSupabaseClient(url: string, key: string): SupabaseClient<Database> {
  return createClient<Database>(url, key, {
    auth: {
      autoRefreshToken: true,
      persistSession: true,
      detectSessionInUrl: true
    },
    realtime: {
      params: {
        eventsPerSecond: 10  // Rate limiting
      }
    }
  })
}

// file: supabase-client/auth.ts
import { SupabaseClient } from '@supabase/supabase-js'

export class AuthService {
  constructor(private supabase: SupabaseClient) {}

  async signup(email: string, password: string, metadata?: any) {
    const { data, error } = await this.supabase.auth.signUp({
      email,
      password,
      options: {
        data: metadata,
        emailRedirectTo: `${window.location.origin}/auth/confirm`
      }
    })
    if (error) throw error
    return data
  }

  async signin(email: string, password: string) {
    const { data, error } = await this.supabase.auth.signInWithPassword({
      email,
      password
    })
    if (error) throw error
    return data.session
  }

  async refreshSession() {
    const { data, error } = await this.supabase.auth.refreshSession()
    if (error) throw error
    return data.session
  }

  async signout() {
    const { error } = await this.supabase.auth.signOut()
    if (error) throw error
  }

  async getCurrentUser() {
    const { data, error } = await this.supabase.auth.getUser()
    if (error) throw error
    return data.user
  }

  async getCurrentSession() {
    const { data, error } = await this.supabase.auth.getSession()
    if (error) throw error
    return data.session
  }
}

// file: supabase-client/bookings.ts
import { SupabaseClient } from '@supabase/supabase-js'
import { Database } from './database.types'

type Booking = Database['public']['Tables']['bookings']['Row']
type BookingInsert = Database['public']['Tables']['bookings']['Insert']

export class BookingService {
  constructor(private supabase: SupabaseClient<Database>) {}

  async findByCarIdAndTimeRange(
    carId: number,
    startTime: string,
    endTime: string
  ): Promise<Booking[]> {
    // Query with RLS automatically enforced
    const { data, error } = await this.supabase
      .from('bookings')
      .select(`
        *,
        car:cars(*),
        renter:users(*)
      `)
      .eq('car_id', carId)
      .in('status', ['PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP'])
      .lt('start_time', endTime)
      .gt('end_time', startTime)

    if (error) throw error
    return data || []
  }

  async checkAvailability(
    carId: number,
    startTime: string,
    endTime: string
  ): Promise<boolean> {
    const conflicts = await this.findByCarIdAndTimeRange(carId, startTime, endTime)
    return conflicts.length === 0
  }

  async createBooking(booking: BookingInsert): Promise<Booking> {
    // Validate idempotency (prevent double-booking on retry)
    const idempotencyKey = booking.idempotency_key
    
    // Check if already created
    if (idempotencyKey) {
      const { data: existing } = await this.supabase
        .from('bookings')
        .select('id')
        .eq('idempotency_key', idempotencyKey)
        .single()
      
      if (existing) return existing as any
    }

    const { data, error } = await this.supabase
      .from('bookings')
      .insert([booking])
      .select()
      .single()

    if (error) {
      // Check if constraint violation (booking conflict)
      if (error.code === '23505') { // Unique violation
        throw new BookingConflictError('Booking overlaps with existing booking')
      }
      throw error
    }

    return data
  }

  async updateBookingStatus(
    bookingId: number,
    status: Booking['status'],
    updateData?: Partial<Booking>
  ): Promise<Booking> {
    const { data, error } = await this.supabase
      .from('bookings')
      .update({
        status,
        updated_at: new Date().toISOString(),
        ...updateData
      })
      .eq('id', bookingId)
      .select()
      .single()

    if (error) throw error
    return data
  }

  // Real-time subscription
  subscribeToBooking(
    bookingId: number,
    callback: (booking: Booking) => void
  ) {
    return this.supabase
      .from(`bookings:id=eq.${bookingId}`)
      .on('*', (payload) => {
        callback(payload.new as Booking)
      })
      .subscribe()
  }
}

// file: supabase-client/cars.ts
export class CarService {
  constructor(private supabase: SupabaseClient<Database>) {}

  async findNearby(
    latitude: number,
    longitude: number,
    radiusKm: number
  ): Promise<Array<Car & { distance_km: number }>> {
    // Call PostgreSQL function (encapsulates spatial logic)
    const { data, error } = await this.supabase.rpc('find_cars_nearby', {
      user_lat: latitude,
      user_lon: longitude,
      radius_km: radiusKm
    })

    if (error) throw error
    return data || []
  }

  async findDeliveryRadius(carId: number, latitude: number, longitude: number): Promise<boolean> {
    const { data, error } = await this.supabase.rpc('check_delivery_radius', {
      car_id: carId,
      user_lat: latitude,
      user_lon: longitude
    })

    if (error) throw error
    return data
  }
}

// file: supabase-client/errors.ts
export class BookingConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'BookingConflictError'
  }
}

export class AuthenticationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthenticationError'
  }
}

export class AuthorizationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthorizationError'
  }
}
```

### 3.2 PostgreSQL Schema Migration (Flyway Conversion)

**OUTPUT**: Create SQL migration files:

```sql
-- file: supabase/migrations/001_create_users_table.sql
CREATE TABLE IF NOT EXISTS public.users (
    id BIGSERIAL PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    phone_number TEXT UNIQUE,
    password_hash TEXT NOT NULL,
    first_name TEXT,
    last_name TEXT,
    date_of_birth DATE,
    profile_picture_url TEXT,
    location TEXT,
    language_preference VARCHAR(10) DEFAULT 'en',
    
    -- Encrypted fields (AES-256-GCM)
    jmbg_encrypted TEXT,  -- Serbian ID number (encrypted)
    pib_encrypted TEXT,   -- Tax ID (encrypted)
    driver_license_encrypted TEXT,
    
    -- Metadata
    email_verified BOOLEAN DEFAULT FALSE,
    phone_verified BOOLEAN DEFAULT FALSE,
    verification_status VARCHAR(50) DEFAULT 'UNVERIFIED',
    
    -- Audit
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    -- Optimistic locking
    version INTEGER DEFAULT 0,
    
    CONSTRAINT users_email_check CHECK (email ~ '^[^@]+@[^@]+\.[^@]+$')
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_verification_status ON users(verification_status);
CREATE INDEX idx_users_created_at ON users(created_at DESC);

-- Enable RLS
ALTER TABLE users ENABLE ROW LEVEL SECURITY;

-- RLS Policy: Users can only read their own profile
CREATE POLICY users_read_self ON users
  FOR SELECT
  USING (auth.uid()::BIGINT = id);

-- RLS Policy: Users can only update their own profile
CREATE POLICY users_update_self ON users
  FOR UPDATE
  USING (auth.uid()::BIGINT = id);

-- RLS Policy: Admins can read all users
CREATE POLICY users_admin_read ON users
  FOR SELECT
  USING (
    EXISTS (
      SELECT 1 FROM user_roles ur
      WHERE ur.user_id = auth.uid()::BIGINT
      AND ur.role = 'admin'
    )
  );

-- Trigger: Update updated_at on changes
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_update_timestamp
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE FUNCTION update_updated_at();

-- file: supabase/migrations/002_create_bookings_table.sql
CREATE TYPE booking_status AS ENUM (
    'PENDING_APPROVAL',
    'ACTIVE',
    'CHECK_IN_OPEN',
    'CHECK_IN_HOST_COMPLETE',
    'CHECK_IN_COMPLETE',
    'IN_TRIP',
    'CHECKOUT_OPEN',
    'CHECKOUT_GUEST_COMPLETE',
    'COMPLETED',
    'CANCELLED',
    'DECLINED',
    'EXPIRED',
    'EXPIRED_SYSTEM',
    'NO_SHOW_HOST',
    'NO_SHOW_GUEST'
);

CREATE TABLE IF NOT EXISTS public.bookings (
    id BIGSERIAL PRIMARY KEY,
    car_id BIGINT NOT NULL REFERENCES cars(id) ON DELETE RESTRICT,
    renter_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- Temporal
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE NOT NULL,
    start_time_utc TIMESTAMP NOT NULL,  -- UTC normalized
    end_time_utc TIMESTAMP NOT NULL,
    
    -- Booking state
    status booking_status DEFAULT 'PENDING_APPROVAL',
    total_price NUMERIC(10, 2),
    currency VARCHAR(3) DEFAULT 'RSD',
    
    -- Check-in/Checkout
    check_in_session_id UUID,
    check_in_opened_at TIMESTAMP WITH TIME ZONE,
    host_check_in_completed_at TIMESTAMP WITH TIME ZONE,
    guest_check_in_completed_at TIMESTAMP WITH TIME ZONE,
    
    checkout_session_id UUID,
    checkout_opened_at TIMESTAMP WITH TIME ZONE,
    
    -- Financial
    security_deposit_amount NUMERIC(10, 2),
    insurance_tier VARCHAR(50),
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMP WITH TIME ZONE,
    decision_deadline_at TIMESTAMP WITH TIME ZONE,
    
    -- Optimistic locking
    version INTEGER DEFAULT 0,
    
    -- Idempotency (prevent double-booking on retry)
    idempotency_key UUID UNIQUE,
    
    CONSTRAINT booking_time_range CHECK (start_time < end_time),
    CONSTRAINT booking_time_utc_range CHECK (start_time_utc < end_time_utc)
);

-- Compound indexes (critical for performance)
CREATE INDEX idx_bookings_car_time ON bookings(car_id, start_time, end_time) 
  WHERE status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP');
CREATE INDEX idx_bookings_renter_time ON bookings(renter_id, start_time, end_time) 
  WHERE status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP');
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_created_at ON bookings(created_at DESC);

ALTER TABLE bookings ENABLE ROW LEVEL SECURITY;

-- RLS Policy: Users see bookings they're involved in
CREATE POLICY bookings_user_access ON bookings
  FOR SELECT
  USING (
    auth.uid()::BIGINT = renter_id 
    OR auth.uid()::BIGINT = (SELECT owner_id FROM cars WHERE id = car_id)
  );

-- RLS Policy: Only renter/owner can update
CREATE POLICY bookings_renter_update ON bookings
  FOR UPDATE
  USING (auth.uid()::BIGINT = renter_id);

CREATE POLICY bookings_owner_update ON bookings
  FOR UPDATE
  USING (auth.uid()::BIGINT = (SELECT owner_id FROM cars WHERE id = car_id));

-- Trigger: Emit event on booking status change
CREATE OR REPLACE FUNCTION broadcast_booking_change()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('booking_changes',
        json_build_object(
            'id', NEW.id,
            'car_id', NEW.car_id,
            'renter_id', NEW.renter_id,
            'status', NEW.status,
            'action', CASE WHEN TG_OP = 'INSERT' THEN 'created' 
                          WHEN TG_OP = 'UPDATE' THEN 'updated'
                          ELSE 'deleted' END
        )::text
    );
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER bookings_broadcast
AFTER INSERT OR UPDATE ON bookings
FOR EACH ROW
EXECUTE FUNCTION broadcast_booking_change();

-- file: supabase/migrations/003_create_cars_table.sql
CREATE TABLE IF NOT EXISTS public.cars (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- Basic info
    brand VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    year INTEGER NOT NULL,
    
    -- Location (GeoPoint)
    location TEXT,
    location_latitude DOUBLE PRECISION,
    location_longitude DOUBLE PRECISION,
    location_point GEOMETRY(POINT, 4326),  -- PostGIS geometry
    location_city VARCHAR(100),
    
    -- Availability
    available BOOLEAN DEFAULT TRUE,
    delivery_radius_km DOUBLE PRECISION,
    
    -- Pricing
    daily_rate NUMERIC(10, 2),
    
    -- Audit
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    -- Optimistic locking
    version INTEGER DEFAULT 0
);

-- PostGIS spatial index
CREATE INDEX idx_cars_location_point ON cars USING GIST(location_point);
CREATE INDEX idx_cars_owner ON cars(owner_id);
CREATE INDEX idx_cars_available ON cars(available);
CREATE INDEX idx_cars_location_city ON cars(location_city);

ALTER TABLE cars ENABLE ROW LEVEL SECURITY;

-- RLS Policy: Public can see available cars
CREATE POLICY cars_public_read ON cars
  FOR SELECT
  USING (available = true);

-- RLS Policy: Owners see their own cars
CREATE POLICY cars_owner_read ON cars
  FOR SELECT
  USING (auth.uid()::BIGINT = owner_id);

-- RLS Policy: Owners can only update their own cars
CREATE POLICY cars_owner_update ON cars
  FOR UPDATE
  USING (auth.uid()::BIGINT = owner_id);

-- Trigger: Maintain location_point from lat/lon
CREATE OR REPLACE FUNCTION update_location_point()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.location_latitude IS NOT NULL AND NEW.location_longitude IS NOT NULL THEN
        NEW.location_point = ST_SetSRID(ST_Point(NEW.location_longitude, NEW.location_latitude), 4326);
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER cars_update_location_point
BEFORE INSERT OR UPDATE ON cars
FOR EACH ROW
EXECUTE FUNCTION update_location_point();

-- file: supabase/functions/find_cars_nearby.sql
CREATE OR REPLACE FUNCTION find_cars_nearby(
    user_lat DOUBLE PRECISION,
    user_lon DOUBLE PRECISION,
    radius_km DOUBLE PRECISION
)
RETURNS TABLE(
    id BIGINT,
    brand VARCHAR,
    model VARCHAR,
    year INTEGER,
    daily_rate NUMERIC,
    distance_km NUMERIC,
    location_city VARCHAR
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        c.id,
        c.brand,
        c.model,
        c.year,
        c.daily_rate,
        (ST_Distance(
            c.location_point::geography,
            ST_Point(user_lon, user_lat)::geography
        ) / 1000)::NUMERIC(10, 2) AS distance_km,
        c.location_city
    FROM cars c
    WHERE c.available = true
    AND ST_DWithin(
        c.location_point::geography,
        ST_Point(user_lon, user_lat)::geography,
        radius_km * 1000  -- Convert to meters
    )
    ORDER BY distance_km ASC;
END
$$ LANGUAGE plpgsql STABLE;

-- file: supabase/functions/check_booking_conflict.sql
CREATE OR REPLACE FUNCTION check_booking_conflict(
    car_id_param BIGINT,
    start_time_param TIMESTAMP WITH TIME ZONE,
    end_time_param TIMESTAMP WITH TIME ZONE
)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS(
        SELECT 1 FROM bookings
        WHERE car_id = car_id_param
        AND status IN (
            'PENDING_APPROVAL'::booking_status,
            'ACTIVE'::booking_status,
            'CHECK_IN_OPEN'::booking_status,
            'CHECK_IN_HOST_COMPLETE'::booking_status,
            'CHECK_IN_COMPLETE'::booking_status,
            'IN_TRIP'::booking_status
        )
        AND start_time < end_time_param
        AND end_time > start_time_param
    );
END
$$ LANGUAGE plpgsql STABLE;
```

### 3.3 Dual-Write Strategy Implementation

```typescript
// file: services/dual-write-service.ts
export class DualWriteService {
  constructor(
    private mysql: MysqlConnection,
    private supabase: SupabaseClient
  ) {}

  /**
   * Write to both MySQL (primary) and PostgreSQL (new) simultaneously
   * Ensures no data loss during migration
   */
  async dualWrite<T>(
    operation: 'insert' | 'update' | 'delete',
    table: string,
    data: T,
    options?: {
      skipMysql?: boolean
      skipSupabase?: boolean
      timeout?: number
    }
  ): Promise<{ mysql: any; supabase: any }> {
    const timeout = options?.timeout || 5000
    const skipMysql = options?.skipMysql || false
    const skipSupabase = options?.skipSupabase || false

    const results = { mysql: null, supabase: null }

    // Write to MySQL (authoritative during transition)
    if (!skipMysql) {
      try {
        const mysqlPromise = this.writeMysql(operation, table, data)
        results.mysql = await Promise.race([
          mysqlPromise,
          new Promise((_, reject) =>
            setTimeout(() => reject(new Error('MySQL timeout')), timeout)
          )
        ])
      } catch (error) {
        console.error(`[DualWrite] MySQL ${operation} failed:`, error)
        // MySQL failure is CRITICAL - abort entire operation
        throw error
      }
    }

    // Write to PostgreSQL (asynchronous)
    if (!skipSupabase) {
      this.writeSupabase(operation, table, data)
        .then((result) => {
          results.supabase = result
          console.log(`[DualWrite] Supabase ${operation} succeeded for ${table}`)
        })
        .catch((error) => {
          console.error(`[DualWrite] Supabase ${operation} failed:`, error)
          // Log to dead-letter queue for manual reconciliation
          this.logDualWriteFailure({
            operation,
            table,
            data,
            error: error.message,
            timestamp: new Date()
          })
        })
    }

    return results
  }

  private async writeMysql(
    operation: 'insert' | 'update' | 'delete',
    table: string,
    data: any
  ): Promise<any> {
    // Implementation depends on MySQL driver (Sequelize, TypeORM, etc.)
    switch (operation) {
      case 'insert':
        return await this.mysql.insert(table, data)
      case 'update':
        return await this.mysql.update(table, data.id, data)
      case 'delete':
        return await this.mysql.delete(table, data.id)
    }
  }

  private async writeSupabase(
    operation: 'insert' | 'update' | 'delete',
    table: string,
    data: any
  ): Promise<any> {
    switch (operation) {
      case 'insert':
        const { data: insertResult, error: insertError } = await this.supabase
          .from(table)
          .insert([data])
          .select()
        if (insertError) throw insertError
        return insertResult
      case 'update':
        const { data: updateResult, error: updateError } = await this.supabase
          .from(table)
          .update(data)
          .eq('id', data.id)
          .select()
        if (updateError) throw updateError
        return updateResult
      case 'delete':
        const { error: deleteError } = await this.supabase
          .from(table)
          .delete()
          .eq('id', data.id)
        if (deleteError) throw deleteError
        return { success: true }
    }
  }

  /**
   * Validate consistency between MySQL and PostgreSQL
   * Run hourly during dual-write period
   */
  async validateConsistency(table: string, ids: number[]): Promise<{
    match: number
    mismatch: number
    missingInSupabase: number
    details: any[]
  }> {
    const results = {
      match: 0,
      mismatch: 0,
      missingInSupabase: 0,
      details: [] as any[]
    }

    for (const id of ids) {
      const mysqlRow = await this.mysql.select(table, id)
      const { data: supabaseRows, error } = await this.supabase
        .from(table)
        .select('*')
        .eq('id', id)

      if (error) {
        results.missingInSupabase++
        results.details.push({
          id,
          issue: 'Not in Supabase',
          error: error.message
        })
        continue
      }

      const supabaseRow = supabaseRows[0]
      if (this.rowsEqual(mysqlRow, supabaseRow)) {
        results.match++
      } else {
        results.mismatch++
        results.details.push({
          id,
          issue: 'Data mismatch',
          mysql: mysqlRow,
          supabase: supabaseRow,
          diff: this.computeDiff(mysqlRow, supabaseRow)
        })
      }
    }

    return results
  }

  private rowsEqual(row1: any, row2: any): boolean {
    // Deep comparison, ignoring timestamps that might differ
    const ignoreFields = ['updated_at', 'synced_at']
    const keys = new Set([...Object.keys(row1), ...Object.keys(row2)])

    for (const key of keys) {
      if (ignoreFields.includes(key)) continue
      if (row1[key] !== row2[key]) return false
    }
    return true
  }

  private computeDiff(row1: any, row2: any): Record<string, any> {
    const diff: Record<string, any> = {}
    const keys = new Set([...Object.keys(row1), ...Object.keys(row2)])

    for (const key of keys) {
      if (row1[key] !== row2[key]) {
        diff[key] = { mysql: row1[key], supabase: row2[key] }
      }
    }
    return diff
  }

  private async logDualWriteFailure(failure: any): Promise<void> {
    // Log to dead-letter queue (RabbitMQ, Kafka, S3, etc.)
    await this.supabase
      .from('dual_write_failures')
      .insert([{
        ...failure,
        status: 'PENDING_RECONCILIATION'
      }])
  }
}
```

### 3.4 Rollback Strategy

```typescript
// file: services/rollback-service.ts
export class RollbackService {
  /**
   * Emergency rollback to MySQL within 30 minutes
   * 1. Stop all writes to PostgreSQL
   * 2. Revert to reading from MySQL
   * 3. Disable feature flags for Supabase reads
   * 4. Log rollback reason for post-mortems
   */
  async emergencyRollback(reason: string): Promise<void> {
    console.error('[Rollback] EMERGENCY ROLLBACK INITIATED')
    console.error(`[Rollback] Reason: ${reason}`)

    // Step 1: Disable Supabase reads immediately
    await this.featureFlagService.setFlag('READ_FROM_SUPABASE', false)
    console.log('[Rollback] Disabled Supabase reads')

    // Step 2: Disable Supabase writes immediately
    await this.featureFlagService.setFlag('WRITE_TO_SUPABASE', false)
    console.log('[Rollback] Disabled Supabase writes')

    // Step 3: Notify on-call team
    await this.alertingService.sendAlert({
      severity: 'CRITICAL',
      title: 'Supabase Migration Rollback',
      message: `Emergency rollback triggered: ${reason}`,
      oncall: true
    })

    // Step 4: Log rollback event (for audit trail)
    await this.auditService.log({
      action: 'MIGRATION_ROLLBACK',
      reason,
      timestamp: new Date(),
      initiatedBy: 'AUTOMATED'
    })

    // Step 5: Verify MySQL is responding
    try {
      await this.mysql.ping()
      console.log('[Rollback] MySQL connectivity verified')
    } catch (error) {
      console.error('[Rollback] CRITICAL: MySQL unavailable!')
      throw new Error('Rollback failed: MySQL unavailable')
    }

    console.log('[Rollback] Rollback completed successfully')
  }

  /**
   * Restore PostgreSQL from backup snapshot
   */
  async restorePostgresToSnapshot(snapshotId: string): Promise<void> {
    console.log(`[Rollback] Restoring PostgreSQL from snapshot ${snapshotId}`)

    // This would be handled by Supabase via API or manual process
    // For now, just log the procedure
    console.log('[Rollback] Contact Supabase support to restore from backup')
    console.log('[Rollback] Estimated time: 15-30 minutes')
  }
}
```

---

## Task 4: Testing & Validation Strategy

### 4.1 Test Plan (With Actual Code)

**OUTPUT**: Create test files for:

```typescript
// file: tests/integration/booking-conflict.test.ts
import { describe, it, expect } from '@jest/globals'
import { SupabaseClient } from '@supabase/supabase-js'
import { BookingService } from '../../src/services/booking-service'

describe('Booking Conflict Detection', () => {
  let bookingService: BookingService
  let supabase: SupabaseClient

  beforeAll(() => {
    // Initialize with test database
    supabase = createTestSupabaseClient()
    bookingService = new BookingService(supabase)
  })

  it('should prevent overlapping bookings (pessimistic locking)', async () => {
    const carId = 1
    const renter1 = 100
    const renter2 = 101

    const startTime = new Date('2025-03-01T10:00:00Z').toISOString()
    const endTime = new Date('2025-03-05T10:00:00Z').toISOString()

    // Renter 1 books car
    const booking1 = await bookingService.createBooking({
      car_id: carId,
      renter_id: renter1,
      start_time: startTime,
      end_time: endTime,
      idempotency_key: 'booking-1'
    })

    expect(booking1.id).toBeDefined()

    // Renter 2 tries to book overlapping dates (should fail)
    await expect(
      bookingService.createBooking({
        car_id: carId,
        renter_id: renter2,
        start_time: '2025-03-02T10:00:00Z',
        end_time: '2025-03-04T10:00:00Z',
        idempotency_key: 'booking-2'
      })
    ).rejects.toThrow(BookingConflictError)
  })

  it('should allow non-overlapping bookings', async () => {
    const carId = 2
    const renter1 = 100
    const renter2 = 101

    const booking1 = await bookingService.createBooking({
      car_id: carId,
      renter_id: renter1,
      start_time: '2025-03-01T10:00:00Z',
      end_time: '2025-03-05T10:00:00Z',
      idempotency_key: 'booking-3'
    })

    // Non-overlapping booking
    const booking2 = await bookingService.createBooking({
      car_id: carId,
      renter_id: renter2,
      start_time: '2025-03-05T10:00:00Z',  // Exactly when booking1 ends
      end_time: '2025-03-10T10:00:00Z',
      idempotency_key: 'booking-4'
    })

    expect(booking2.id).toBeDefined()
    expect(booking2.id).not.toEqual(booking1.id)
  })

  it('should handle idempotency (retry safety)', async () => {
    const carId = 3
    const renter = 100
    const idempotencyKey = 'unique-booking-key'

    // First attempt
    const booking1 = await bookingService.createBooking({
      car_id: carId,
      renter_id: renter,
      start_time: '2025-04-01T10:00:00Z',
      end_time: '2025-04-05T10:00:00Z',
      idempotency_key: idempotencyKey
    })

    // Retry with same idempotency key (should return same booking)
    const booking2 = await bookingService.createBooking({
      car_id: carId,
      renter_id: renter,
      start_time: '2025-04-01T10:00:00Z',
      end_time: '2025-04-05T10:00:00Z',
      idempotency_key: idempotencyKey
    })

    expect(booking1.id).toEqual(booking2.id)
  })
})

// file: tests/integration/rls-enforcement.test.ts
describe('Row-Level Security Enforcement', () => {
  it('should prevent user from viewing other user bookings', async () => {
    const user1Client = createSupabaseClient(user1Token)
    const user2Client = createSupabaseClient(user2Token)

    // User1 has booking ID 100
    // User2 tries to access it (should be blocked by RLS)
    const { data, error } = await user2Client
      .from('bookings')
      .select('*')
      .eq('id', 100)

    expect(data).toEqual([])  // RLS returned empty, not error
  })

  it('should allow owner to see their car bookings', async () => {
    const ownerClient = createSupabaseClient(ownerToken)
    const carId = 1  // Owner's car

    const { data, error } = await ownerClient
      .from('bookings')
      .select('*')
      .eq('car_id', carId)

    expect(data?.length).toBeGreaterThan(0)
  })

  it('should allow admin to bypass RLS for investigation', async () => {
    // Supabase supports admin bypass via service role key
    const adminClient = createSupabaseAdminClient()

    const { data, error } = await adminClient
      .from('bookings')
      .select('*')

    expect(data?.length).toBeGreaterThan(0)  // Admin sees all
  })
})

// file: tests/integration/realtime-subscriptions.test.ts
describe('Real-Time Subscriptions', () => {
  it('should broadcast booking status changes', async (done) => {
    const bookingId = 100
    let messageReceived = false

    const subscription = supabase
      .from(`bookings:id=eq.${bookingId}`)
      .on('*', (payload) => {
        messageReceived = true
        expect(payload.new.status).toEqual('ACTIVE')
        done()
      })
      .subscribe()

    // Simulate status change
    setTimeout(async () => {
      await supabase
        .from('bookings')
        .update({ status: 'ACTIVE' })
        .eq('id', bookingId)
    }, 100)
  }, 5000)
})

// file: tests/chaos/network-failure.test.ts
describe('Chaos Engineering: Network Failures', () => {
  it('should gracefully handle Supabase connection timeout', async () => {
    // Simulate network partition
    const timeoutClient = new SupabaseClient(url, key, {
      realtime: { params: { eventsPerSecond: 0 } }  // Disable realtime
    })

    // Should fail gracefully (not crash)
    const promise = timeoutClient
      .from('bookings')
      .select('*')
      .timeout(100)  // 100ms timeout

    await expect(promise).rejects.toThrow()
  })
})
```

### 4.2 Monitoring & Observability

```typescript
// file: monitoring/migration-dashboard.ts
export class MigrationDashboard {
  /**
   * Key metrics to track during migration
   */
  async getMetrics(): Promise<MigrationMetrics> {
    return {
      // Data consistency
      rowCountMySQL: await this.countRows('mysql', '*'),
      rowCountPostgres: await this.countRows('postgres', '*'),
      rowCountDifference: Math.abs(
        await this.countRows('mysql', '*') - await this.countRows('postgres', '*')
      ),

      // Performance
      queryLatencyP50: await this.getLatencyPercentile(50),
      queryLatencyP95: await this.getLatencyPercentile(95),
      queryLatencyP99: await this.getLatencyPercentile(99),

      // Errors
      recentErrors: await this.getRecentErrors(5),
      dualWriteFailures: await this.getDualWriteFailures(),

      // Feature flags
      readFromSupabase: await this.featureFlagService.isEnabled('READ_FROM_SUPABASE'),
      writeToSupabase: await this.featureFlagService.isEnabled('WRITE_TO_SUPABASE'),

      // Alerts
      activeAlerts: await this.getActiveAlerts()
    }
  }

  /**
   * Alert thresholds
   */
  private thresholds = {
    rowCountDifference: 100,  // Alert if > 100 rows differ
    latencyP99: 1000,         // Alert if > 1 second
    errorRate: 0.001,         // Alert if > 0.1% errors
    dualWriteFailures: 5      // Alert if > 5 failures
  }

  async checkThresholds(): Promise<Alert[]> {
    const metrics = await this.getMetrics()
    const alerts: Alert[] = []

    if (metrics.rowCountDifference > this.thresholds.rowCountDifference) {
      alerts.push({
        severity: 'CRITICAL',
        message: `Row count difference: ${metrics.rowCountDifference}`,
        action: 'INVESTIGATE_DATA_SYNC'
      })
    }

    if (metrics.queryLatencyP99 > this.thresholds.latencyP99) {
      alerts.push({
        severity: 'HIGH',
        message: `P99 latency: ${metrics.queryLatencyP99}ms`,
        action: 'CHECK_QUERY_PERFORMANCE'
      })
    }

    if (metrics.dualWriteFailures > this.thresholds.dualWriteFailures) {
      alerts.push({
        severity: 'CRITICAL',
        message: `Dual-write failures: ${metrics.dualWriteFailures}`,
        action: 'RECONCILE_WRITES'
      })
    }

    return alerts
  }
}
```

---

## Task 5: Execution Checklist & Runbooks

### 5.1 Pre-Migration Checklist

```markdown
## Week -1: Preparation

- [ ] **Infrastructure**
  - [ ] Provision Supabase project (Pro tier)
  - [ ] Configure CORS, allowed origins
  - [ ] Enable PostGIS extension
  - [ ] Configure backups (daily snapshots)
  - [ ] Set up monitoring (Grafana, Prometheus)

- [ ] **Team & Communication**
  - [ ] Kick-off meeting (all 6 engineers)
  - [ ] Assign owners (backend, frontend, DevOps, QA)
  - [ ] Create on-call rotation (24/7 during cutover)
  - [ ] Document runbooks (in Confluence/Notion)
  - [ ] Set up Slack notifications (#migration-alert)

- [ ] **Backup & Rollback**
  - [ ] MySQL snapshot created + verified
  - [ ] Backup stored in cold storage (S3 Glacier)
  - [ ] Restore procedure tested (< 30 min)
  - [ ] Document rollback decision criteria

- [ ] **Security Review**
  - [ ] Encryption keys in AWS Secrets Manager (not hardcoded)
  - [ ] RLS policies reviewed by security team
  - [ ] CORS allowlist verified
  - [ ] API rate limiting configured

- [ ] **Feature Flags Setup**
  - [ ] FLAG: READ_FROM_SUPABASE (default: false)
  - [ ] FLAG: WRITE_TO_SUPABASE (default: false)
  - [ ] FLAG: DUAL_WRITE_MODE (default: false)
  - [ ] Gradual rollout configuration (5% → 25% → 50% → 100%)

- [ ] **Documentation**
  - [ ] Architecture diagram (current vs target)
  - [ ] Data flow diagrams (MySQL → PostgreSQL)
  - [ ] Runbook: Emergency rollback
  - [ ] Runbook: Consistency validation
  - [ ] Runbook: Incident response
```

### 5.2 Migration Week Runbook

```markdown
## MONDAY - Schema Migration

09:00 - Team standup
10:00 - Backup MySQL (full snapshot + checksums)
11:00 - Create PostgreSQL schema (Flyway migrations)
12:00 - Validate schema (foreign keys, constraints, indexes)
13:00 - Create RLS policies (28 policies)
14:00 - Create PostgreSQL functions (find_cars_nearby, etc.)
15:00 - Test RLS enforcement (policy unit tests)
16:00 - Final schema validation (pg_dump comparison)
17:00 - Prepare data import script (mysqldump → pg_restore)

## TUESDAY - Data Import & Validation

09:00 - Export MySQL data (mysqldump --single-transaction)
10:00 - Import to PostgreSQL (pg_restore)
11:00 - Validate row counts (must match exactly)
12:00 - Validate foreign key integrity (constraints)
13:00 - Validate unique constraints (no duplicates)
14:00 - Compare checksums (table-level SHA256)
15:00 - Final cleanup (sequence max values, etc.)
16:00 - Create backups (PostgreSQL snapshot)
17:00 - Decision: Proceed with dual-write (YES/NO)

## WEDNESDAY-THURSDAY - Dual-Write & Backend

Enable dual-write: writes to MySQL + PostgreSQL simultaneously
09:00 - Deploy dual-write service
10:00 - Monitor consistency checks (hourly)
11:00 - Backend engineers port repositories (67 methods)
12:00 - Feature flag: WRITE_TO_SUPABASE = 5% canary
13:00 - Monitor error logs (Sentry)
14:00 - Gradual rollout: 5% → 25% → 50% → 100%
15:00 - Run chaos tests (network failures, timeouts)
16:00 - Performance comparison (latency, throughput)
17:00 - QA sign-off

## FRIDAY - Read Cutover & Frontend

09:00 - Feature flag: READ_FROM_SUPABASE = 5% canary
10:00 - Monitor query results (spot checks)
11:00 - Gradual rollout: 5% → 25% → 50% → 100%
12:00 - Frontend engineers integrate Supabase client
13:00 - Update authentication flow (JWT → Supabase Auth)
14:00 - Test real-time subscriptions (chat, bookings)
15:00 - End-to-end testing (Playwright tests)
16:00 - Load testing (1K concurrent users)
17:00 - Final decision: Go/No-Go for full cutover

## WEEK 2 - Production Cutover (WEEKEND)

FRIDAY (Day 5):
15:00 - Lock MySQL (read-only mode)
16:00 - Final consistency check (100% match required)
17:00 - Prepare DNS cutover (reduce TTL to 1 minute)
18:00 - Notify users (SMS + in-app banner)

SATURDAY (Maintenance window: 2-4 hours)
00:00 - Disable dual-write (MySQL in read-only)
00:15 - Disable feature flags (force PostgreSQL)
00:30 - Update connection string (app → PostgreSQL)
00:45 - Validate: Can users login? Can they book?
01:00 - Monitor error rates (Sentry, Grafana)
01:30 - Gradual user rebalancing (close MySQL connections)
02:00 - Status: GREEN (all systems nominal)

SUNDAY (Post-cutover monitoring)
00:00 - 24h: Monitor error rates, latency, business metrics
- p50, p95, p99 latency
- Error rate < 0.1%
- Payment success rate > 99.5%
- Zero data loss

MONDAY (Day 1 post)
- Debrief: What went well? What didn't?
- Document lessons learned
- Clean up dual-write code
- MySQL can be decommissioned (or kept as read replica)
```

---

## Task 6: Final Deliverables

**OUTPUT STRUCTURE** (organize in `/Users/kljaja01/Developer/Rentoza/MIGRATION_ARTIFACTS/`):

```
MIGRATION_ARTIFACTS/
├── 01_ANALYSIS/
│   ├── codebase-inventory.md          # Entity list, query patterns
│   ├── dependency-graph.md             # Data relationships
│   ├── third-party-integrations.md     # Stripe, Firebase, etc.
│   └── frontend-analysis.md            # Components, services, modules
│
├── 02_MIGRATION_PLAN/
│   ├── phase-timeline.md               # Week-by-week breakdown
│   ├── data-pipeline.md                # MySQL → PG strategy
│   ├── rls-policies.md                 # All 28+ policies
│   ├── risk-mitigation.md              # Failure modes + solutions
│   └── rollback-procedures.md          # Emergency playbook
│
├── 03_CODE/
│   ├── supabase-client/
│   │   ├── index.ts
│   │   ├── auth.ts
│   │   ├── bookings.ts
│   │   ├── cars.ts
│   │   ├── users.ts
│   │   ├── notifications.ts
│   │   ├── realtime.ts
│   │   ├── errors.ts
│   │   └── types.ts (auto-generated from Supabase)
│   │
│   ├── migrations/
│   │   ├── 001_users.sql
│   │   ├── 002_cars.sql
│   │   ├── 003_bookings.sql
│   │   ├── 004_check_in_photos.sql
│   │   ├── ... (all tables)
│   │   └── 999_create_functions.sql
│   │
│   ├── services/
│   │   ├── dual-write.ts
│   │   ├── rollback.ts
│   │   ├── consistency-validator.ts
│   │   └── feature-flags.ts
│   │
│   └── tests/
│       ├── integration/booking-conflicts.test.ts
│       ├── integration/rls-enforcement.test.ts
│       ├── integration/realtime-subscriptions.test.ts
│       └── chaos/network-failure.test.ts
│
├── 04_MONITORING/
│   ├── migration-dashboard.ts          # Metrics + alerts
│   ├── grafana-dashboard.json          # Pre-built dashboard
│   └── alerting-rules.yaml             # Prometheus rules
│
├── 05_RUNBOOKS/
│   ├── pre-migration-checklist.md      # Go/no-go decisions
│   ├── week-by-week-runbook.md         # Daily tasks
│   ├── emergency-rollback.md           # Step-by-step procedures
│   ├── consistency-validation.md       # Validation procedures
│   └── incident-response.md            # Playbooks for common issues
│
└── 06_DOCUMENTATION/
    ├── executive-summary.md            # For stakeholders
    ├── technical-deep-dive.md          # For engineers
    ├── faq.md                          # Common questions
    └── glossary.md                     # Terms & definitions
```

---

## Task 7: Execution & Sign-Off

**REQUIRED OUTPUTS:**

1. **Detailed week-by-week code** (100+ files, production-ready)
2. **SQL migrations** (all 28 tables, RLS policies, functions)
3. **TypeScript client library** (type-safe, fully tested)
4. **Test suites** (integration, chaos, load tests)
5. **Monitoring & alerting** (Grafana, Prometheus, PagerDuty)
6. **Runbooks & playbooks** (step-by-step procedures)
7. **Risk assessment** (failure modes, mitigation strategies)
8. **Rollback procedures** (< 30 minutes to MySQL)
9. **Team training** (docs, video, hands-on lab)
10. **Go/No-Go checklist** (automated validation)

---

## Summary for AI Agent

This prompt asks you to:

1. **Analyze** the codebase exhaustively (no assumptions)
2. **Map** all dependencies, integrations, transactions
3. **Design** a production-grade migration strategy
4. **Code** the solution (TypeScript, SQL, tests)
5. **Validate** with comprehensive testing
6. **Monitor** migration progress in real-time
7. **Execute** with zero downtime and zero data loss
8. **Rollback** if needed (< 30 minutes)

**Success Criteria:**
- ✅ All 67 repositories migrated to Supabase PostgREST
- ✅ All 28 RLS policies enforced (zero data leaks)
- ✅ Real-time subscriptions < 100ms latency
- ✅ Payment transactions 99.95% success rate
- ✅ Load test: 5K concurrent users (zero errors)
- ✅ Security audit: Zero critical findings
- ✅ User impact: ZERO downtime

**Timeline**: 8-10 weeks (phased, parallelizable)

**Ownership**: 5-6 engineers + DevOps

---

**END OF PROMPT**
