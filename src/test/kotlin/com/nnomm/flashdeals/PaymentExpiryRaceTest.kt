package com.nnomm.flashdeals

import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.models.ClaimRequest
import com.nnomm.flashdeals.models.CreateDealRequest
import com.nnomm.flashdeals.models.PaymentWebhookRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PaymentExpiryRaceTest : BaseIntegrationTest() {

    @Test
    fun `Payment after expires_at marks claim REFUND_PENDING and returns units to stock`() {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(
                title = "Late Payment Deal",
                price_paise = 1000,
                stock_total = 10,
                starts_at = Instant.now().minusSeconds(10).toString(),
                ends_at = Instant.now().plusSeconds(3600).toString()
            )
        )
        val dealId = UUID.fromString(deal.id)
        val customer = UserPrincipal(UUID.randomUUID(), "customer")

        val claimResult = claimService.claimDeal(
            principal = customer,
            dealId = dealId,
            idempotencyKeyHeader = "late-payment-key-${UUID.randomUUID()}",
            req = ClaimRequest(quantity = 2)
        )
        assertEquals(201, claimResult.statusCode)

        val claimIdRegex = """"id":"([^"]+)"""".toRegex()
        val claimId = UUID.fromString(claimIdRegex.find(claimResult.responseBody)!!.groupValues[1])

        // Manually adjust expires_at to be in the past to simulate exact expiry
        db.withTransaction { conn ->
            conn.prepareStatement("UPDATE claims SET expires_at = NOW() - INTERVAL '5 seconds' WHERE id = ?").use { stmt ->
                stmt.setObject(1, claimId)
                stmt.executeUpdate()
            }
        }

        // Webhook arrives after expires_at has passed
        paymentWebhookService.processWebhook(
            PaymentWebhookRequest(
                event_id = "evt_late_${UUID.randomUUID()}",
                type = "payment.succeeded",
                claim_id = claimId.toString(),
                amount_paise = 2000,
                occurred_at = Instant.now().toString()
            )
        )

        // Claim must end in REFUND_PENDING
        val updatedClaim = claimService.getClaim(customer, claimId)
        assertEquals("REFUND_PENDING", updatedClaim.status)

        // Stock must have been returned (stock_total = 10, stock_available = 10)
        val updatedDeal = dealService.getDeal(dealId)
        assertEquals(10, updatedDeal.stock_available, "Stock must be fully returned to 10")
    }

    @Test
    fun `A payment and expiry racing on the same claim leave the stock invariant intact`() = runBlocking {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val initialStock = 20
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(
                title = "Race Deal",
                price_paise = 1000,
                stock_total = initialStock,
                starts_at = Instant.now().minusSeconds(10).toString(),
                ends_at = Instant.now().plusSeconds(3600).toString()
            )
        )
        val dealId = UUID.fromString(deal.id)

        // Create 20 claims
        val claims = (1..initialStock).map { i ->
            val customer = UserPrincipal(UUID.randomUUID(), "customer")
            val res = claimService.claimDeal(
                principal = customer,
                dealId = dealId,
                idempotencyKeyHeader = "race-key-$i-${UUID.randomUUID()}",
                req = ClaimRequest(quantity = 1)
            )
            val claimId = UUID.fromString(""""id":"([^"]+)"""".toRegex().find(res.responseBody)!!.groupValues[1])
            claimId to customer
        }

        // Force all claims expires_at to be right around NOW()
        db.withTransaction { conn ->
            conn.prepareStatement("UPDATE claims SET expires_at = NOW() WHERE deal_id = ?").use { stmt ->
                stmt.setObject(1, dealId)
                stmt.executeUpdate()
            }
        }

        // Concurrently race payment webhooks and expiry job on all 20 claims
        val deferreds = claims.map { (claimId, _) ->
            listOf(
                async(Dispatchers.IO) {
                    try {
                        paymentWebhookService.processWebhook(
                            PaymentWebhookRequest(
                                event_id = "evt_race_${UUID.randomUUID()}",
                                type = "payment.succeeded",
                                claim_id = claimId.toString(),
                                amount_paise = 1000,
                                occurred_at = Instant.now().toString()
                            )
                        )
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        expiryWorker.processExpiredClaimsBatch(batchSize = 20)
                    } catch (_: Exception) {}
                }
            )
        }.flatten()

        deferreds.awaitAll()

        // Wait a brief moment for any remaining async sweeps
        expiryWorker.processExpiredClaimsBatch(batchSize = 50)

        // Invariant Check:
        // Every claim must end in PAID or REFUND_PENDING or EXPIRED.
        // stock_available + sum(held units: RESERVED, PAID, NEEDS_REVIEW) == initialStock (20)
        val updatedDeal = dealService.getDeal(dealId)

        db.withConnection { conn ->
            val sql = """
                SELECT status, count(*), sum(quantity)
                FROM claims
                WHERE deal_id = ?
                GROUP BY status
            """.trimIndent()

            var paidUnits = 0
            var reservedUnits = 0
            var needsReviewUnits = 0
            var refundPendingUnits = 0
            var expiredUnits = 0

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, dealId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val status = rs.getString("status")
                        val units = rs.getInt(3)
                        when (status) {
                            "PAID" -> paidUnits += units
                            "RESERVED" -> reservedUnits += units
                            "NEEDS_REVIEW" -> needsReviewUnits += units
                            "REFUND_PENDING" -> refundPendingUnits += units
                            "EXPIRED" -> expiredUnits += units
                        }
                    }
                }
            }

            // Invariant: stock_available + held units == initialStock
            val unitsHeld = paidUnits + reservedUnits + needsReviewUnits
            val totalCalculated = updatedDeal.stock_available + unitsHeld
            assertEquals(
                initialStock,
                totalCalculated,
                "Stock invariant violated: stock_available (${updatedDeal.stock_available}) + held units ($unitsHeld) != total ($initialStock)"
            )
            assertTrue(updatedDeal.stock_available >= 0, "stock_available must never drop below 0")
        }
    }
}
