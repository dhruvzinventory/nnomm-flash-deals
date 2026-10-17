package com.nnomm.flashdeals

import com.nnomm.flashdeals.auth.ConflictException
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyTest : BaseIntegrationTest() {

    @Test
    fun `200 or more concurrent claims never oversell a 50-unit deal`() = runBlocking {
        // 1. Create a deal with 50 units
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val startsAt = Instant.now().minusSeconds(60).toString()
        val endsAt = Instant.now().plusSeconds(3600).toString()

        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(
                title = "Concurrent Biryani Deal",
                price_paise = 1000,
                stock_total = 50,
                starts_at = startsAt,
                ends_at = endsAt
            )
        )
        val dealId = UUID.fromString(deal.id)

        // 2. Launch 250 concurrent claims from 250 distinct customers
        val concurrentUsers = 250
        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)
        val otherErrorCount = AtomicInteger(0)

        val deferreds = (1..concurrentUsers).map { i ->
            async(Dispatchers.IO) {
                val customerId = UUID.randomUUID()
                val customer = UserPrincipal(customerId, "customer")
                val key = "key-user-$i-${UUID.randomUUID()}"

                try {
                    val result = claimService.claimDeal(
                        principal = customer,
                        dealId = dealId,
                        idempotencyKeyHeader = key,
                        req = ClaimRequest(quantity = 1)
                    )
                    if (result.statusCode == 201) {
                        successCount.incrementAndGet()
                    } else if (result.statusCode == 409 && result.responseBody.contains("SOLD_OUT")) {
                        soldOutCount.incrementAndGet()
                    } else {
                        otherErrorCount.incrementAndGet()
                    }
                } catch (e: ConflictException) {
                    if (e.code == "SOLD_OUT") {
                        soldOutCount.incrementAndGet()
                    } else {
                        otherErrorCount.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    otherErrorCount.incrementAndGet()
                }
            }
        }

        deferreds.awaitAll()

        // 3. Verify exactly 50 succeeded, exactly 200 failed with SOLD_OUT, 0 unexpected errors
        assertEquals(50, successCount.get(), "Exactly 50 claims should have succeeded")
        assertEquals(200, soldOutCount.get(), "Remaining 200 claims should have failed with SOLD_OUT")
        assertEquals(0, otherErrorCount.get(), "There should be no unexpected errors")

        // 4. Verify database state & invariants
        val updatedDeal = dealService.getDeal(dealId)
        assertEquals(0, updatedDeal.stock_available, "Available stock must be exactly 0")

        db.withConnection { conn ->
            val countClaimsSql = "SELECT count(*) FROM claims WHERE deal_id = ? AND status = 'RESERVED'"
            conn.prepareStatement(countClaimsSql).use { stmt ->
                stmt.setObject(1, dealId)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(50, rs.getInt(1), "Exactly 50 RESERVED claims must exist in the database")
                }
            }
        }
    }
}
