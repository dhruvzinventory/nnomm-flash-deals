package com.nnomm.flashdeals

import com.nnomm.flashdeals.auth.ConflictException
import com.nnomm.flashdeals.auth.IdempotencyKeyReusedException
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.models.ClaimRequest
import com.nnomm.flashdeals.models.CreateDealRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class IdempotencyTest : BaseIntegrationTest() {

    @Test
    fun `20 or more concurrent requests with one Idempotency-Key create exactly one claim`() = runBlocking {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(
                title = "Idempotency Test Deal",
                price_paise = 2500,
                stock_total = 10,
                starts_at = Instant.now().minusSeconds(10).toString(),
                ends_at = Instant.now().plusSeconds(3600).toString()
            )
        )
        val dealId = UUID.fromString(deal.id)

        val customerId = UUID.randomUUID()
        val customer = UserPrincipal(customerId, "customer")
        val idempotencyKey = "shared-idempotency-key-${UUID.randomUUID()}"

        val totalRequests = 25
        val successResponses = AtomicInteger(0)
        val replayedResponses = AtomicInteger(0)
        val inProgressResponses = AtomicInteger(0)

        val deferreds = (1..totalRequests).map {
            async(Dispatchers.IO) {
                try {
                    val result = claimService.claimDeal(
                        principal = customer,
                        dealId = dealId,
                        idempotencyKeyHeader = idempotencyKey,
                        req = ClaimRequest(quantity = 2)
                    )
                    if (result.statusCode == 201) {
                        if (result.isReplayed) {
                            replayedResponses.incrementAndGet()
                        } else {
                            successResponses.incrementAndGet()
                        }
                    }
                } catch (e: ConflictException) {
                    if (e.code == "REQUEST_IN_PROGRESS") {
                        inProgressResponses.incrementAndGet()
                    }
                }
            }
        }

        deferreds.awaitAll()

        // Exactly one created the claim (original 201)
        assertEquals(1, successResponses.get(), "Exactly 1 request should create the claim")
        // All other requests either received a replayed 201 or 409 REQUEST_IN_PROGRESS
        assertEquals(totalRequests - 1, replayedResponses.get() + inProgressResponses.get())

        // Verify only 1 claim in DB
        db.withConnection { conn ->
            conn.prepareStatement("SELECT count(*) FROM claims WHERE customer_id = ? AND deal_id = ?").use { stmt ->
                stmt.setObject(1, customerId)
                stmt.setObject(2, dealId)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1), "Exactly 1 claim row must exist in DB")
                }
            }
        }

        // Sequential replay should now guarantee replayed = true and status 201
        val sequentialReplay = claimService.claimDeal(
            principal = customer,
            dealId = dealId,
            idempotencyKeyHeader = idempotencyKey,
            req = ClaimRequest(quantity = 2)
        )
        assertEquals(201, sequentialReplay.statusCode)
        assertTrue(sequentialReplay.isReplayed)
    }

    @Test
    fun `Concurrent claims by one customer with different keys create exactly one claim`() = runBlocking {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(
                title = "One Claim Per Customer Deal",
                price_paise = 1500,
                stock_total = 10,
                starts_at = Instant.now().minusSeconds(10).toString(),
                ends_at = Instant.now().plusSeconds(3600).toString()
            )
        )
        val dealId = UUID.fromString(deal.id)

        val customerId = UUID.randomUUID()
        val customer = UserPrincipal(customerId, "customer")

        val totalRequests = 10
        val createdCount = AtomicInteger(0)
        val alreadyClaimedCount = AtomicInteger(0)

        val deferreds = (1..totalRequests).map { i ->
            async(Dispatchers.IO) {
                val uniqueKey = "customer-key-$i-${UUID.randomUUID()}"
                val result = claimService.claimDeal(
                    principal = customer,
                    dealId = dealId,
                    idempotencyKeyHeader = uniqueKey,
                    req = ClaimRequest(quantity = 1)
                )
                if (result.statusCode == 201) {
                    createdCount.incrementAndGet()
                } else if (result.statusCode == 409 && result.responseBody.contains("ALREADY_CLAIMED")) {
                    alreadyClaimedCount.incrementAndGet()
                }
            }
        }

        deferreds.awaitAll()

        assertEquals(1, createdCount.get(), "Exactly 1 claim should be created for this customer")
        assertEquals(totalRequests - 1, alreadyClaimedCount.get(), "All other concurrent attempts must fail with ALREADY_CLAIMED")

        // Database must have only 1 active claim
        db.withConnection { conn ->
            conn.prepareStatement("SELECT count(*) FROM claims WHERE customer_id = ? AND deal_id = ?").use { stmt ->
                stmt.setObject(1, customerId)
                stmt.setObject(2, dealId)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `Same key with different deal or quantity throws 422 IDEMPOTENCY_KEY_REUSED`() {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val deal1 = dealService.createDeal(
            admin,
            CreateDealRequest(title = "Deal 1", price_paise = 2000, stock_total = 5, starts_at = Instant.now().minusSeconds(5).toString(), ends_at = Instant.now().plusSeconds(3600).toString())
        )
        val deal2 = dealService.createDeal(
            admin,
            CreateDealRequest(title = "Deal 2", price_paise = 3000, stock_total = 5, starts_at = Instant.now().minusSeconds(5).toString(), ends_at = Instant.now().plusSeconds(3600).toString())
        )

        val customer = UserPrincipal(UUID.randomUUID(), "customer")
        val sharedKey = "reused-key-${UUID.randomUUID()}"

        // First call with quantity 1
        val res1 = claimService.claimDeal(customer, UUID.fromString(deal1.id), sharedKey, ClaimRequest(quantity = 1))
        assertEquals(201, res1.statusCode)

        // Attempt reuse with different quantity
        assertThrows<IdempotencyKeyReusedException> {
            claimService.claimDeal(customer, UUID.fromString(deal1.id), sharedKey, ClaimRequest(quantity = 2))
        }

        // Attempt reuse with different deal
        assertThrows<IdempotencyKeyReusedException> {
            claimService.claimDeal(customer, UUID.fromString(deal2.id), sharedKey, ClaimRequest(quantity = 1))
        }
    }
}
