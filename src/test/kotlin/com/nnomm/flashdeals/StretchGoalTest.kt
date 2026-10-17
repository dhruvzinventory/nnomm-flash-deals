package com.nnomm.flashdeals

import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.models.ClaimRequest
import com.nnomm.flashdeals.models.CreateDealRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StretchGoalTest : BaseIntegrationTest() {

    @Test
    fun `Cursor pagination never skips or repeats claims across multiple pages`() {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(title = "Pagination Deal", price_paise = 1000, stock_total = 100, starts_at = Instant.now().minusSeconds(10).toString(), ends_at = Instant.now().plusSeconds(3600).toString())
        )
        val dealId = UUID.fromString(deal.id)
        val customer = UserPrincipal(UUID.randomUUID(), "customer")

        // Customer creates 10 claims over time (by expiring previous ones so they can claim again)
        val claimIds = mutableListOf<String>()
        for (i in 1..7) {
            val res = claimService.claimDeal(
                customer,
                dealId,
                "pag-key-$i-${UUID.randomUUID()}",
                ClaimRequest(quantity = 1)
            )
            val cId = """"id":"([^"]+)"""".toRegex().find(res.responseBody)!!.groupValues[1]
            claimIds.add(cId)

            // Mark expired so customer can make next claim
            db.withTransaction { conn ->
                conn.prepareStatement("UPDATE claims SET status = 'EXPIRED' WHERE id = ?").use {
                    it.setObject(1, UUID.fromString(cId))
                    it.executeUpdate()
                }
            }
        }

        // Fetch page 1 with limit 3
        val page1 = stretchService.getCustomerClaims(customer, cursor = null, limit = 3)
        assertEquals(3, page1.claims.size)
        assertTrue(page1.has_more)
        assertTrue(page1.next_cursor != null)

        // Fetch page 2 with limit 3 using cursor from page 1
        val page2 = stretchService.getCustomerClaims(customer, cursor = page1.next_cursor, limit = 3)
        assertEquals(3, page2.claims.size)
        assertTrue(page2.has_more)
        assertTrue(page2.next_cursor != null)

        // Fetch page 3 with limit 3
        val page3 = stretchService.getCustomerClaims(customer, cursor = page2.next_cursor, limit = 3)
        assertEquals(1, page3.claims.size)
        assertFalse(page3.has_more)

        // Collect all paginated IDs
        val retrievedIds = (page1.claims + page2.claims + page3.claims).map { it.id }

        // Assert all 7 unique claims retrieved, no duplicates, no missing items
        assertEquals(7, retrievedIds.size)
        assertEquals(retrievedIds.toSet().size, retrievedIds.size, "No duplicate claims across pages")
    }

    @Test
    fun `Rate limiter enforces limit across distributed instances`() {
        val customerId = UUID.randomUUID()

        // 10 attempts allowed
        for (i in 1..10) {
            val allowed = stretchService.checkAndRecordRateLimit(customerId, maxPerMinute = 10)
            assertTrue(allowed, "Attempt $i should be allowed")
        }

        // 11th attempt within 1 minute must be rejected
        val eleventh = stretchService.checkAndRecordRateLimit(customerId, maxPerMinute = 10)
        assertFalse(eleventh, "11th attempt within 1 minute must be blocked")
    }

    @Test
    fun `Admin can query claims for their deal with status filter`() {
        val restaurantId = UUID.randomUUID()
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantId)
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(title = "Filter Deal", price_paise = 1000, stock_total = 10, starts_at = Instant.now().minusSeconds(10).toString(), ends_at = Instant.now().plusSeconds(3600).toString())
        )
        val dealId = UUID.fromString(deal.id)

        // Create 2 claims
        val cust1 = UserPrincipal(UUID.randomUUID(), "customer")
        val res1 = claimService.claimDeal(cust1, dealId, "key1", ClaimRequest(quantity = 1))
        val c1Id = UUID.fromString(""""id":"([^"]+)"""".toRegex().find(res1.responseBody)!!.groupValues[1])

        val cust2 = UserPrincipal(UUID.randomUUID(), "customer")
        val res2 = claimService.claimDeal(cust2, dealId, "key2", ClaimRequest(quantity = 1))
        val c2Id = UUID.fromString(""""id":"([^"]+)"""".toRegex().find(res2.responseBody)!!.groupValues[1])

        // Mark claim 2 as PAID
        db.withTransaction { conn ->
            conn.prepareStatement("UPDATE claims SET status = 'PAID' WHERE id = ?").use {
                it.setObject(1, c2Id)
                it.executeUpdate()
            }
        }

        // Query with status filter = PAID
        val paidClaims = stretchService.getDealClaimsForAdmin(admin, dealId, "PAID")
        assertEquals(1, paidClaims.size)
        assertEquals(c2Id.toString(), paidClaims[0].id)

        // Query with status filter = RESERVED
        val reservedClaims = stretchService.getDealClaimsForAdmin(admin, dealId, "RESERVED")
        assertEquals(1, reservedClaims.size)
        assertEquals(c1Id.toString(), reservedClaims[0].id)

        // Query with no filter (all)
        val allClaims = stretchService.getDealClaimsForAdmin(admin, dealId, null)
        assertEquals(2, allClaims.size)
    }
}
