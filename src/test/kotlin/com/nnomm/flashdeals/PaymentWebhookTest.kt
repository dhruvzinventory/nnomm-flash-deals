package com.nnomm.flashdeals

import com.nnomm.flashdeals.auth.AuthenticationException
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.models.ClaimRequest
import com.nnomm.flashdeals.models.CreateDealRequest
import com.nnomm.flashdeals.models.PaymentWebhookRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class PaymentWebhookTest : BaseIntegrationTest() {

    @Test
    fun `Bad signatures and stale timestamps are rejected`() {
        val rawBody = """{"event_id":"evt_1","type":"payment.succeeded","claim_id":"${UUID.randomUUID()}","amount_paise":1000,"occurred_at":"${Instant.now()}"}"""

        // 1. Missing header
        assertThrows<AuthenticationException> {
            webhookVerifier.verify(null, rawBody.toByteArray())
        }

        // 2. Tampered signature (wrong secret)
        val badSig = generateWebhookSignature(rawBody, secret = "wrong-secret-key-1234567890123456")
        assertThrows<AuthenticationException> {
            webhookVerifier.verify(badSig, rawBody.toByteArray())
        }

        // 3. Stale timestamp (350 seconds ago)
        val staleSig = generateWebhookSignature(rawBody, timestamp = Instant.now().epochSecond - 350)
        assertThrows<AuthenticationException> {
            webhookVerifier.verify(staleSig, rawBody.toByteArray())
        }

        // 4. Future timestamp (350 seconds in future)
        val futureSig = generateWebhookSignature(rawBody, timestamp = Instant.now().epochSecond + 350)
        assertThrows<AuthenticationException> {
            webhookVerifier.verify(futureSig, rawBody.toByteArray())
        }

        // 5. Valid signature succeeds
        val validSig = generateWebhookSignature(rawBody)
        assertDoesNotThrow {
            webhookVerifier.verify(validSig, rawBody.toByteArray())
        }
    }

    @Test
    fun `Duplicate events, amount mismatch, and payment failed are handled correctly`() {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(title = "Webhook Deal", price_paise = 2000, stock_total = 10, starts_at = Instant.now().minusSeconds(10).toString(), ends_at = Instant.now().plusSeconds(3600).toString())
        )
        val customer = UserPrincipal(UUID.randomUUID(), "customer")
        val claimResult = claimService.claimDeal(customer, UUID.fromString(deal.id), "webhook-key-${UUID.randomUUID()}", ClaimRequest(quantity = 2))
        assertEquals(201, claimResult.statusCode)

        // Parse claimId from response
        val claimIdRegex = """"id":"([^"]+)"""".toRegex()
        val claimId = UUID.fromString(claimIdRegex.find(claimResult.responseBody)!!.groupValues[1])

        // 1. Send payment.failed event -> no change, claim stays RESERVED
        val failEventId = "evt_fail_${UUID.randomUUID()}"
        paymentWebhookService.processWebhook(
            PaymentWebhookRequest(
                event_id = failEventId,
                type = "payment.failed",
                claim_id = claimId.toString(),
                amount_paise = 4000,
                occurred_at = Instant.now().toString()
            )
        )
        val claimAfterFail = claimService.getClaim(customer, claimId)
        assertEquals("RESERVED", claimAfterFail.status)

        // 2. Duplicate payment.failed event -> ignored
        paymentWebhookService.processWebhook(
            PaymentWebhookRequest(
                event_id = failEventId,
                type = "payment.failed",
                claim_id = claimId.toString(),
                amount_paise = 4000,
                occurred_at = Instant.now().toString()
            )
        )
        assertEquals("RESERVED", claimService.getClaim(customer, claimId).status)

        // 3. Amount mismatch -> claim status becomes NEEDS_REVIEW
        val mismatchEventId = "evt_mismatch_${UUID.randomUUID()}"
        paymentWebhookService.processWebhook(
            PaymentWebhookRequest(
                event_id = mismatchEventId,
                type = "payment.succeeded",
                claim_id = claimId.toString(),
                amount_paise = 3000, // Expected 4000 (2 * 2000)
                occurred_at = Instant.now().toString()
            )
        )
        val claimAfterMismatch = claimService.getClaim(customer, claimId)
        assertEquals("NEEDS_REVIEW", claimAfterMismatch.status)

        // 4. Duplicate mismatch event -> no change
        paymentWebhookService.processWebhook(
            PaymentWebhookRequest(
                event_id = mismatchEventId,
                type = "payment.succeeded",
                claim_id = claimId.toString(),
                amount_paise = 3000,
                occurred_at = Instant.now().toString()
            )
        )
        assertEquals("NEEDS_REVIEW", claimService.getClaim(customer, claimId).status)

        // 5. Unknown claim_id handled gracefully without throwing
        assertDoesNotThrow {
            paymentWebhookService.processWebhook(
                PaymentWebhookRequest(
                    event_id = "evt_unknown_${UUID.randomUUID()}",
                    type = "payment.succeeded",
                    claim_id = UUID.randomUUID().toString(),
                    amount_paise = 5000,
                    occurred_at = Instant.now().toString()
                )
            )
        }
    }
}
