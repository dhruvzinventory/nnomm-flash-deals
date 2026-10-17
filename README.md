# Nnomm Flash-Deal Claim Service

A high-concurrency flash-deal claim and payment webhook service built with **Kotlin (JVM 17)**, **Spring Boot 3.3.4**, and **PostgreSQL 16**.

Engineered to remain strictly correct under heavy concurrency, network retries, duplicate/out-of-order payment webhooks, and distributed execution across multiple instances sharing a single database.

---

## Architecture & Key Invariants

1. **Never Oversell**: Guaranteed via PostgreSQL row-level locks (`SELECT ... FOR UPDATE`) and DB check constraints (`stock_available >= 0`). Stock moves out on reservation and only moves back if a claim expires or is refunded.
2. **One Active Claim Per Customer Per Deal**: Enforced at the database engine level via a partial unique index:
   ```sql
   CREATE UNIQUE INDEX idx_claims_customer_deal_active ON claims (customer_id, deal_id)
   WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
   ```
3. **Idempotency**: Scoped to `(customer_id, idempotency_key)`. Concurrent retries receive replayed responses with header `Idempotent-Replayed: true` or `409 REQUEST_IN_PROGRESS`. Process crashes release after a 60-second recovery timeout.
4. **Payment vs. Expiry Concurrency**: Handled with deterministic row locks. If payment arrives after `expires_at`, it never marks a claim `PAID`; the claim transitions to `REFUND_PENDING` and units are returned to stock.
5. **Multi-Instance Coordination**: Two instances run concurrently sharing one database. The background claim expiration job uses `SELECT ... FOR UPDATE SKIP LOCKED` to allow worker instances to process batches without deadlocks or contention.

---

## How to Run

### Option 1: Docker Compose (Two Instances + PostgreSQL)
```bash
docker compose up --build
```
This starts:
- `db`: PostgreSQL 16 on port `5432`
- `app1`: Nnomm Service Instance 1 on port `8080`
- `app2`: Nnomm Service Instance 2 on port `8081`

Both application instances share the PostgreSQL database and run database migrations automatically via Flyway on startup.

### Option 2: Running Locally
Ensure PostgreSQL is running on port 5432, then:
```bash
export DATABASE_URL="jdbc:postgresql://localhost:5432/nnomm"
export DATABASE_USER="nnomm"
export DATABASE_PASSWORD="nnomm_local_dev"
export JWT_SECRET="super-secret-jwt-key-for-local-testing-purposes-123"
export WEBHOOK_SECRET="super-secret-webhook-key-for-local-testing-123"
export CLAIM_TTL_SECONDS="600"
export PORT="8080"

./gradlew bootRun
```

---

## How to Test

Run the complete test suite against PostgreSQL:
```bash
./gradlew test
```

### Verified Test Suite
The test suite validates all required invariants and stretch requirements:
1. `ConcurrencyTest`: **250 concurrent claims** competing for a **50-unit deal** — exactly 50 succeed (201), exactly 200 receive `SOLD_OUT` (409), available stock ends at exactly 0.
2. `IdempotencyTest`: **25 concurrent requests** with the same `Idempotency-Key` create exactly **1 claim**. Replays return identical status and body with `Idempotent-Replayed: true`. Key reuse with different parameters returns `422 IDEMPOTENCY_KEY_REUSED`.
3. `IdempotencyTest`: Concurrent claims by the same customer with different keys create exactly **1 claim**; all others receive `409 ALREADY_CLAIMED`.
4. `PaymentWebhookTest`: Verifies HMAC-SHA256 signature, rejects stale/future timestamps (>300s), handles duplicate event IDs idempotently, transitions wrong amounts to `NEEDS_REVIEW`, and ignores `payment.failed` without altering reservation.
5. `PaymentExpiryRaceTest`: Simulates high-concurrency races between payment webhooks and the expiry worker on the same claims. Validates that stock invariants hold in all scenarios: `stock_available + sum(held units) == stock_total`.
6. `AuthAndVisibilityTest`: Validates that customers cannot read other customers' claims, admins cannot read claims for other restaurants' deals (returns `404 NOT_FOUND` per brief, never 403), strictly rejects JWT `none` algorithm and expired/tampered tokens.
7. `StretchGoalTest`: Tests keyset cursor pagination (`GET /v1/me/claims`) across multiple pages, distributed rate limiting (10 req/min), and admin claim queries with status filtering.
8. `FullServerIntegrationTest`: End-to-end Spring MockMvc HTTP tests covering deal creation, claim reservation, idempotency replay headers, webhooks, 16 KB body limit (`413 PAYLOAD_TOO_LARGE`), and Prometheus `/metrics`.

---

## Assumptions Where Brief Was Silent

1. **Unknown `claim_id` on Webhook**:
   Payment providers (like Stripe or Razorpay) retry webhooks upon receiving 4xx/5xx responses. If an unknown `claim_id` arrives, retrying will never succeed. I record a warning log and acknowledge with `200 OK` to prevent webhook clogging while keeping state clean.
2. **Duplicate `payment.succeeded` with New `event_id`**:
   If a second successful payment event arrives for an already finalized claim (`PAID`, `NEEDS_REVIEW`, `REFUND_PENDING`), the system records the event in `payment_events` for audit/accounting purposes, logs a duplicate payment alert, and returns `200 OK` without modifying the claim's status or stock.
3. **Visibility & 404 vs 403**:
   Per the brief's security requirements: `GET /v1/claims/{claimId}` returns `404 NOT_FOUND` (not 403) if the authenticated user is not the owning customer or the owning restaurant's admin. This prevents attackers from probing whether arbitrary claim IDs exist.
4. **Real-Time Expiry on Read**:
   In addition to the asynchronous background worker running `FOR UPDATE SKIP LOCKED` sweeps, read operations (`GET /v1/claims/{claimId}`) lazily check if `expires_at <= NOW()` and expire the claim atomically. This guarantees that from `expires_at` on, the API never displays a claim as `RESERVED`.

---

## What I Would Do Next with More Time

1. **Redis Atomic Reservation for 100x Scale**:
   Implement an in-memory Redis token reservation layer using atomic Lua scripts (`DECRBY`) to handle 100,000+ requests/sec before persisting to PostgreSQL.
2. **Transactional Outbox & Event Streaming**:
   Use the Transactional Outbox pattern with Debezium / Kafka to publish claim and payment domain events to kitchen display systems and notification services asynchronously.
3. **OpenTelemetry & Distributed Tracing**:
   Add OpenTelemetry instrumentation for distributed request tracing across services, database queries, and webhook deliveries.

---

## AI Tools Used & Rejected Suggestions

- **AI Tools Used**: Used Claude and Gemini for initial code scaffolding, Gradle setup, and exploring concurrency test patterns.
- **Suggestion Rejected & Why**:
  - *Rejected Suggestion*: The AI initially suggested using in-memory Kotlin coroutine mutexes (`kotlinx.coroutines.sync.Mutex`) or Java `ReentrantLock` to protect stock deductions.
  - *Why Rejected*: In-memory mutexes only protect concurrency inside a single JVM heap. The specification strictly requires two or more instances sharing a single database. Mutexes would fail completely across two running instances, leading to overselling. I rejected in-memory locks and implemented database-level pessimistic locking (`SELECT ... FOR UPDATE`), atomic database updates, and partial unique constraints.
