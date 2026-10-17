package com.nnomm.flashdeals

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.flashdeals.auth.AuthenticationException
import com.nnomm.flashdeals.auth.ForbiddenException
import com.nnomm.flashdeals.auth.NotFoundException
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.models.ClaimRequest
import com.nnomm.flashdeals.models.CreateDealRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Date
import java.util.UUID

class AuthAndVisibilityTest : BaseIntegrationTest() {

    @Test
    fun `Customers cannot read other customers claims and admins cannot read other restaurants claims`() {
        val restaurantA = UUID.randomUUID()
        val restaurantB = UUID.randomUUID()

        val adminA = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantA)
        val adminB = UserPrincipal(UUID.randomUUID(), "restaurant_admin", restaurantB)

        // Deal for Restaurant A
        val dealA = dealService.createDeal(
            adminA,
            CreateDealRequest(title = "Rest A Deal", price_paise = 1000, stock_total = 10, starts_at = Instant.now().minusSeconds(10).toString(), ends_at = Instant.now().plusSeconds(3600).toString())
        )
        val dealAId = UUID.fromString(dealA.id)

        val customer1 = UserPrincipal(UUID.randomUUID(), "customer")
        val customer2 = UserPrincipal(UUID.randomUUID(), "customer")

        // Customer 1 claims on Deal A
        val claimResult = claimService.claimDeal(
            principal = customer1,
            dealId = dealAId,
            idempotencyKeyHeader = "vis-key-${UUID.randomUUID()}",
            req = ClaimRequest(quantity = 1)
        )
        val claimIdRegex = """"id":"([^"]+)"""".toRegex()
        val claimId = UUID.fromString(claimIdRegex.find(claimResult.responseBody)!!.groupValues[1])

        // 1. Customer 1 can read their own claim
        val readBySelf = claimService.getClaim(customer1, claimId)
        assertEquals(claimId.toString(), readBySelf.id)

        // 2. Admin A (restaurant of the deal) can read the claim
        val readByAdminA = claimService.getClaim(adminA, claimId)
        assertEquals(claimId.toString(), readByAdminA.id)

        // 3. Customer 2 gets 404 NOT_FOUND (never 403)
        val exCust2 = assertThrows<NotFoundException> {
            claimService.getClaim(customer2, claimId)
        }
        assertEquals("Claim not found", exCust2.message)

        // 4. Admin B (different restaurant) gets 404 NOT_FOUND (never 403)
        val exAdminB = assertThrows<NotFoundException> {
            claimService.getClaim(adminB, claimId)
        }
        assertEquals("Claim not found", exAdminB.message)
    }

    @Test
    fun `Role checks enforce that only admins create deals and only customers claim`() {
        val customer = UserPrincipal(UUID.randomUUID(), "customer")
        val admin = UserPrincipal(UUID.randomUUID(), "restaurant_admin", UUID.randomUUID())

        // Customer trying to create deal -> 403 FORBIDDEN
        assertThrows<ForbiddenException> {
            dealService.createDeal(
                customer,
                CreateDealRequest(title = "Illegal Deal", price_paise = 1000, stock_total = 5, starts_at = Instant.now().toString(), ends_at = Instant.now().plusSeconds(100).toString())
            )
        }

        // Deal created by admin
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest(title = "Valid Deal", price_paise = 1000, stock_total = 5, starts_at = Instant.now().minusSeconds(5).toString(), ends_at = Instant.now().plusSeconds(100).toString())
        )

        // Admin trying to claim deal -> 403 FORBIDDEN
        assertThrows<ForbiddenException> {
            claimService.claimDeal(
                principal = admin,
                dealId = UUID.fromString(deal.id),
                idempotencyKeyHeader = "admin-claim-key",
                req = ClaimRequest(quantity = 1)
            )
        }
    }

    @Test
    fun `JWT verification strictly rejects none algorithm, expired tokens, and invalid signatures`() {
        val userId = UUID.randomUUID()

        // 1. Valid token
        val validToken = jwtService.mintToken(userId, "customer")
        val principal = jwtService.verify(validToken)
        assertEquals(userId, principal.userId)
        assertEquals("customer", principal.role)

        // 2. Algorithm 'none' token
        val noneToken = JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", "customer")
            .sign(Algorithm.none())
        assertThrows<AuthenticationException> {
            jwtService.verify(noneToken)
        }

        // 3. Expired token
        val expiredToken = JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", "customer")
            .withExpiresAt(Date.from(Instant.now().minusSeconds(100)))
            .sign(Algorithm.HMAC256(jwtSecret))
        assertThrows<AuthenticationException> {
            jwtService.verify(expiredToken)
        }

        // 4. Tampered token
        val tamperedToken = validToken.substring(0, validToken.length - 4) + "abcd"
        assertThrows<AuthenticationException> {
            jwtService.verify(tamperedToken)
        }
    }
}
