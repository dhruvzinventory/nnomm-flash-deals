package com.nnomm.flashdeals

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeals.models.ClaimResponse
import com.nnomm.flashdeals.models.DealResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
class FullServerIntegrationTest : BaseIntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `Full HTTP lifecycle test - Deals, Claims, Webhooks, Replay headers, and Body Limits`() {
        val restaurantId = UUID.randomUUID()
        val adminToken = mintAdminToken(restaurantId)
        val customerId = UUID.randomUUID()
        val customerToken = mintCustomerToken(customerId)

        // 1. Create deal
        val startsAt = Instant.now().minusSeconds(10).toString()
        val endsAt = Instant.now().plusSeconds(3600).toString()
        val dealCreateBody = """{"title":"HTTP Deal","price_paise":5000,"stock_total":10,"starts_at":"$startsAt","ends_at":"$endsAt"}"""

        val createDealRes = mockMvc.perform(
            post("/v1/deals")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dealCreateBody)
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val deal = objectMapper.readValue(createDealRes, DealResponse::class.java)
        assertEquals("HTTP Deal", deal.title)
        assertEquals(10, deal.stock_available)

        // 2. Get deal
        mockMvc.perform(
            get("/v1/deals/${deal.id}")
                .header("Authorization", "Bearer $customerToken")
        ).andExpect(status().isOk)

        // 3. Claim units
        val key = "http-idempotency-key-${UUID.randomUUID()}"
        val claimRes = mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $customerToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":2}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val claim = objectMapper.readValue(claimRes, ClaimResponse::class.java)
        assertEquals(2, claim.quantity)
        assertEquals(10000, claim.amount_paise)
        assertEquals("RESERVED", claim.status)

        // 4. Idempotent replay: same key, same deal and quantity
        mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $customerToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":2}""")
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))

        // 5. Get claim by customer
        mockMvc.perform(
            get("/v1/claims/${claim.id}")
                .header("Authorization", "Bearer $customerToken")
        ).andExpect(status().isOk)

        // 6. Payment webhook
        val eventId = "evt_http_${UUID.randomUUID()}"
        val webhookBody = """{"event_id":"$eventId","type":"payment.succeeded","claim_id":"${claim.id}","amount_paise":10000,"occurred_at":"${Instant.now()}"}"""
        val sigHeader = generateWebhookSignature(webhookBody)

        mockMvc.perform(
            post("/v1/webhooks/payments")
                .header("X-Payment-Signature", sigHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(webhookBody.toByteArray(Charsets.UTF_8))
        ).andExpect(status().isOk)

        // Verify claim is now PAID
        val claimPaidRes = mockMvc.perform(
            get("/v1/claims/${claim.id}")
                .header("Authorization", "Bearer $customerToken")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val claimPaid = objectMapper.readValue(claimPaidRes, ClaimResponse::class.java)
        assertEquals("PAID", claimPaid.status)

        // 7. Test Body size limit: > 16 KB -> 413 PAYLOAD_TOO_LARGE
        val hugeBody = "a".repeat(17000)
        val largeRes = mockMvc.perform(
            post("/v1/deals")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"$hugeBody","price_paise":100,"stock_total":1,"starts_at":"$startsAt","ends_at":"$endsAt"}""")
        ).andExpect(status().isPayloadTooLarge).andReturn().response.contentAsString
        assertTrue(largeRes.contains("PAYLOAD_TOO_LARGE"))

        // 8. Test Malformed Idempotency-Key -> 400 IDEMPOTENCY_KEY_INVALID
        val badKeyRes = mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $customerToken")
                .header("Idempotency-Key", "invalid key with spaces @#$$%")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":1}""")
        ).andExpect(status().isBadRequest).andReturn().response.contentAsString
        assertTrue(badKeyRes.contains("IDEMPOTENCY_KEY_INVALID"))

        // 9. Metrics endpoint
        mockMvc.perform(get("/metrics")).andExpect(status().isOk)
    }
}
