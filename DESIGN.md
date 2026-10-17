# DESIGN.md — Nnomm Flash-Deal Claim Service Design

## 1. Two Customers Claiming the Last Unit at the Same Instant

### SQL Walkthrough & PostgreSQL Behavior
When two customers on different instances attempt to claim the last unit at the exact same instant, both requests enter `ClaimService.processClaimInsideTransaction` ([ClaimService.kt](src/main/kotlin/com/nnomm/flashdeals/service/ClaimService.kt)):

1. **Transaction Start & Row Lock**:
   Both instances issue:
   ```sql
   SELECT id, price_paise, stock_available, starts_at, ends_at
   FROM deals
   WHERE id = ?
   FOR UPDATE;
   ```
2. **PostgreSQL Row-Level Lock Queue**:
   PostgreSQL’s lock manager grants an exclusive tuple lock (`XMAX`) to the first transaction (Tx1) that reaches the disk/buffer page for the deal row. The second transaction (Tx2) is placed into a blocked wait queue (`tuple lock wait`) waiting on Tx1's transaction ID.
3. **Execution of Tx1**:
   Tx1 reads `stock_available = 1`. Since `1 >= quantity`, it decrements stock:
   ```sql
   UPDATE deals SET stock_available = stock_available - 1 WHERE id = ?;
   INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at)
   VALUES (?, ?, ?, 1, 1000, 'RESERVED', ?, NOW(), NOW());
   ```
   Tx1 commits and releases the row lock.
4. **Execution of Tx2**:
   PostgreSQL awakens Tx2. Under `READ COMMITTED` isolation, PostgreSQL re-evaluates the updated version of the deal row committed by Tx1. Tx2 now reads `stock_available = 0`.
   Tx2 evaluates `stock_available < quantity` (0 < 1), halts claim creation, stores the `409 SOLD_OUT` outcome in `idempotency_records`, and commits without modifying stock.

### Why a Kotlin Mutex or `synchronized` Block Fails
A Kotlin `Mutex` or Java `synchronized` block exists solely in the heap memory of a single JVM process. When running two or more distributed instances (Instance 1 on port 8080 and Instance 2 on port 8081), process memory is completely isolated. Instance 1's mutex has zero awareness of Instance 2's locks. Only the shared database layer (PostgreSQL's row-level locks and write-ahead log) can coordinate synchronization across multiple nodes.

---

## 2. Process Crashes and Idempotency Guarantees

Idempotency state is managed in `idempotency_records` ([V3 migration](src/main/resources/db/migration/V3__create_idempotency_table.sql) & [ClaimService.kt](src/main/kotlin/com/nnomm/flashdeals/service/ClaimService.kt)):

### Scenario A: Service commits the claim, then crashes before responding
1. The transaction committed both the claim insertion in `claims` and the `COMPLETED` record with `response_status_code = 201` and the serialized response body in `idempotency_records`.
2. When the client retries with the same `Idempotency-Key`, `executeClaimWithIdempotency` locks the row with `SELECT ... FOR UPDATE`.
3. It finds `status = 'COMPLETED'` and age < 24 hours.
4. It immediately replays the original 201 JSON body with HTTP header `Idempotent-Replayed: true`.
5. No second claim is created, stock is not deducted again, and the client recovers transparently.

### Scenario B: Service crashes just before the commit
1. If the JVM process crashes or the network drops before `conn.commit()` succeeds, PostgreSQL automatically rolls back the entire transaction.
2. Neither the claim nor the `idempotency_records` row is persisted (or if the initial lock insert was in an uncommitted transaction, it is rolled back as well).
3. If an `IN_PROGRESS` row was committed in a preceding phase, its `locked_at` timestamp remains.
4. When the client retries:
   - If the crash rolled back the insert, the retry inserts a fresh `IN_PROGRESS` record and safely executes.
   - If an `IN_PROGRESS` record remained, after 60 seconds (`ageSeconds > 60`), `executeClaimWithIdempotency` detects the abandoned/crashed lock, reclaims it via `UPDATE ... SET locked_at = NOW()`, and re-executes.
5. In either case, zero duplicate claims are created, and keys are never locked indefinitely.

---

## 3. Webhook and Expiry Race Ordering & Outcomes

Both `PaymentWebhookService.processWebhook` ([PaymentWebhookService.kt](src/main/kotlin/com/nnomm/flashdeals/service/PaymentWebhookService.kt)) and `ClaimExpiryWorker.processExpiredClaimsBatch` ([ClaimExpiryWorker.kt](src/main/kotlin/com/nnomm/flashdeals/service/ClaimExpiryWorker.kt)) acquire row-level locks on `claims` via `FOR UPDATE` or `FOR UPDATE SKIP LOCKED`. Exactly one transaction acquires the row lock first.

### Ordering 1: Expiry Job Wins Lock First
1. Expiry worker reads `status = 'RESERVED'` and `expires_at <= NOW()`.
2. Expiry worker updates `claims.status = 'EXPIRED'` and restores stock (`deals.stock_available += quantity`). Commits.
3. Webhook worker acquires lock and reads `status = 'EXPIRED'`.
4. As specified in the brief's state matrix, late payment on an `EXPIRED` claim transitions `status = 'REFUND_PENDING'`, while units are **not** taken back (units stay in stock).
- **Final Claim Status**: `REFUND_PENDING`
- **Final Stock**: Initial stock restored (`+quantity`).

### Ordering 2: Payment Webhook Wins Lock First (Payment on Time)
1. Webhook reads `status = 'RESERVED'` and `NOW() < expires_at`.
2. If amount matches: transitions `status = 'PAID'`. Units remain held/sold. Commits.
   (If amount differs: transitions `status = 'NEEDS_REVIEW'`, units held. Commits.)
3. Expiry worker later queries for claims with `status = 'RESERVED' AND expires_at <= NOW()`.
4. Because status is now `PAID` (or `NEEDS_REVIEW`), the expiry query ignores the claim completely.
- **Final Claim Status**: `PAID` (or `NEEDS_REVIEW`)
- **Final Stock**: Units remain sold (stock stays decremented).

### Ordering 3: Payment Webhook Wins Lock First (Payment Past Expiry Time)
1. Webhook reads `status = 'RESERVED'`, but detects `NOW() >= expires_at`.
2. Rule 5 states: *"A payment processed after expires_at never marks a claim PAID... the claim ends REFUND_PENDING with its units returned."*
3. Webhook updates `status = 'REFUND_PENDING'` and restores stock (`deals.stock_available += quantity`). Commits.
4. Expiry worker runs later; finds `status = 'REFUND_PENDING'`, so it ignores the row.
- **Final Claim Status**: `REFUND_PENDING`
- **Final Stock**: Initial stock restored (`+quantity`).

**Stock Invariant Holds in All Orderings**: `stock_available + sum(RESERVED, PAID, NEEDS_REVIEW) == stock_total`.

---

## 4. Webhook Security: Raw Body, Timestamps, and Timing Attacks

### Why Cover Raw Body Bytes Rather Than Parsed JSON?
JSON serialization is non-canonical. Parsed JSON objects lose key ordering, whitespace, newline differences, and number formatting (e.g., `{"a":1,"b":2}` vs `{"b": 2, "a": 1}`). If the receiver re-serialized parsed JSON to verify the HMAC signature, any minor formatting disparity would produce a completely different SHA-256 hash, causing spurious signature failures. Signing raw bytes guarantees bit-for-bit authenticity exactly as transmitted.

### What Does the Timestamp (`t`) Protect Against?
The timestamp protects against **replay attacks**. If an eavesdropper intercepts a valid signed webhook request, they could repeatedly replay that request hours or days later. Enforcing `|t - now| <= 300` ensures that intercepted requests expire and cannot be replayed past 5 minutes.

### Why Constant-Time Signature Comparison?
Standard string equality (`==`) terminates comparison at the first mismatched byte. An attacker can measure nanosecond differences in network response times to incrementally guess the HMAC signature byte-by-byte. Using `MessageDigest.isEqual()` ensures that comparison time is constant regardless of how many bytes match, preventing timing side-channel attacks.

---

## 5. Transaction Isolation Level & Concurrency Anomalies

### Chosen Level: `READ COMMITTED` with Explicit Pessimistic Locking
I chose PostgreSQL's default `READ COMMITTED` isolation level, paired with explicit pessimistic locking (`SELECT ... FOR UPDATE` and `FOR UPDATE SKIP LOCKED`).

### Why It Is Sufficient
Under `READ COMMITTED`, `SELECT ... FOR UPDATE` locks the target row. Any concurrent transaction attempting to read or update that same row blocks until the first transaction commits or rolls back. When unblocked, PostgreSQL re-evaluates the updated row against the query conditions. This completely serializes stock reservations per deal without serialization failure retries.

### Anomaly Seen with a Weaker Approach (Plain `READ COMMITTED` without `FOR UPDATE`)
Without `FOR UPDATE`, two concurrent transactions could both execute:
```sql
SELECT stock_available FROM deals WHERE id = ?; -- Both read 1
-- Both see stock >= 1
UPDATE deals SET stock_available = stock_available - 1 WHERE id = ?;
```
This causes a classic **Lost Update** anomaly: both transactions decrement 1 from the original value of 1, resulting in `stock_available = 0`, but 2 claims are created for 1 unit of stock (**overselling**).

---

## 6. Most Critical Constraint / Index

### The Workhorse: `idx_claims_customer_deal_active`
```sql
CREATE UNIQUE INDEX idx_claims_customer_deal_active 
ON claims (customer_id, deal_id) 
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
```
([V2 migration](src/main/resources/db/migration/V2__create_claims_table.sql)).

### What Breaks If Dropped?
If an operator drops this partial unique index:
When a customer sends 50 concurrent claim requests with different idempotency keys across two service instances, application-level checks (`SELECT count(*) FROM claims WHERE ...`) can suffer from a **phantom read race condition**. Both instances read 0 active claims simultaneously and proceed to insert.
Without this index enforcing uniqueness at the storage engine level, the customer would succeed in securing multiple active reservations for the same flash deal, violating Rule 2.

---

## 7. Scaling 100× When a Deal Goes Viral

### What Fails First?
1. **Row-level lock contention on PostgreSQL**: When thousands of requests simultaneously attempt `SELECT ... FOR UPDATE` on the single viral deal row, PostgreSQL connection pools saturate (`max_connections` / Hikari pool exhaustion) and lock wait queues trigger transaction timeouts (`statement_timeout`).
2. **Database CPU spikes** due to lock contention overhead rather than actual throughput.

### How I Would Evolve the Architecture for 100× Scale:
1. **Redis Atomic Reservation (`DECRBY`) / Token Bucket**:
   Pre-load `stock_available` into Redis when a deal goes live. Use atomic Lua scripts (`redis.call('DECRBY', key, qty)`) in memory (100k+ ops/sec) to reserve units before hitting the database.
2. **Asynchronous Write Queue via Kafka / RabbitMQ**:
   Once Redis grants the reservation, push the claim creation event into an asynchronous queue. A pool of background consumers persists claims and idempotency records to PostgreSQL in micro-batches, completely decoupling user-facing response times from database disk I/O.
3. **Optimistic Stock Allocation via Read Replicas**:
   Route read traffic (`GET /v1/deals/{id}`) to read replicas with aggressive HTTP caching (e.g., Cache-Control and CDN edge cache for 1 second).
