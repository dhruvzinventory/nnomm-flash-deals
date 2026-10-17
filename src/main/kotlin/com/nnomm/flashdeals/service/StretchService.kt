package com.nnomm.flashdeals.service

import com.nnomm.flashdeals.auth.ForbiddenException
import com.nnomm.flashdeals.auth.NotFoundException
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.models.ClaimResponse
import com.nnomm.flashdeals.models.PaginatedClaimsResponse
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@Service
class StretchService(private val dataSource: DataSource) {

    fun checkAndRecordRateLimit(customerId: UUID, maxPerMinute: Int = 10): Boolean {
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM claim_rate_limits WHERE attempt_at < NOW() - INTERVAL '2 minutes'").use {
                    it.executeUpdate()
                }

                val countSql = """
                    SELECT count(*) 
                    FROM claim_rate_limits 
                    WHERE customer_id = ? AND attempt_at >= NOW() - INTERVAL '1 minute'
                """.trimIndent()

                var count = 0
                conn.prepareStatement(countSql).use { stmt ->
                    stmt.setObject(1, customerId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            count = rs.getInt(1)
                        }
                    }
                }

                val allowed = if (count >= maxPerMinute) {
                    false
                } else {
                    conn.prepareStatement("INSERT INTO claim_rate_limits (customer_id, attempt_at) VALUES (?, NOW())").use { stmt ->
                        stmt.setObject(1, customerId)
                        stmt.executeUpdate()
                    }
                    true
                }

                conn.commit()
                allowed
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    fun getCustomerClaims(
        principal: UserPrincipal,
        cursor: String?,
        limit: Int = 20
    ): PaginatedClaimsResponse {
        val safeLimit = limit.coerceIn(1, 100)
        val customerId = principal.userId

        var cursorTimestamp: Instant? = null
        var cursorId: UUID? = null

        if (!cursor.isNullOrBlank()) {
            try {
                val decoded = String(Base64.getUrlDecoder().decode(cursor))
                val parts = decoded.split(":")
                if (parts.size == 2) {
                    cursorTimestamp = Instant.ofEpochMilli(parts[0].toLong())
                    cursorId = UUID.fromString(parts[1])
                }
            } catch (e: Exception) {
                // Ignore malformed cursor, fall back to start
            }
        }

        return dataSource.connection.use { conn ->
            val sql = if (cursorTimestamp != null && cursorId != null) {
                """
                    SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at
                    FROM claims
                    WHERE customer_id = ? 
                      AND (created_at < ? OR (created_at = ? AND id < ?))
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                """.trimIndent()
            } else {
                """
                    SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at
                    FROM claims
                    WHERE customer_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                """.trimIndent()
            }

            val claims = mutableListOf<ClaimResponse>()
            var lastCreatedAt: Instant? = null
            var lastId: UUID? = null
            var hasMore = false

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, customerId)
                if (cursorTimestamp != null && cursorId != null) {
                    stmt.setTimestamp(2, Timestamp.from(cursorTimestamp))
                    stmt.setTimestamp(3, Timestamp.from(cursorTimestamp))
                    stmt.setObject(4, cursorId)
                    stmt.setInt(5, safeLimit + 1)
                } else {
                    stmt.setInt(2, safeLimit + 1)
                }

                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val cId = rs.getObject("id") as UUID
                        val dealId = rs.getObject("deal_id") as UUID
                        val custId = rs.getObject("customer_id") as UUID
                        val qty = rs.getInt("quantity")
                        val amount = rs.getInt("amount_paise")
                        val status = rs.getString("status")
                        val expiresAt = rs.getTimestamp("expires_at").toInstant()
                        val createdAt = rs.getTimestamp("created_at").toInstant()

                        if (claims.size < safeLimit) {
                            claims.add(
                                ClaimResponse(
                                    id = cId.toString(),
                                    deal_id = dealId.toString(),
                                    customer_id = custId.toString(),
                                    quantity = qty,
                                    amount_paise = amount,
                                    status = status,
                                    expires_at = expiresAt.toString()
                                )
                            )
                            lastCreatedAt = createdAt
                            lastId = cId
                        } else {
                            hasMore = true
                        }
                    }
                }
            }

            val nextCursor = if (hasMore && lastCreatedAt != null && lastId != null) {
                val token = "${lastCreatedAt.toEpochMilli()}:$lastId"
                Base64.getUrlEncoder().encodeToString(token.toByteArray())
            } else null

            PaginatedClaimsResponse(
                claims = claims,
                next_cursor = nextCursor,
                has_more = hasMore
            )
        }
    }

    fun getDealClaimsForAdmin(
        principal: UserPrincipal,
        dealId: UUID,
        statusFilter: String?
    ): List<ClaimResponse> {
        if (principal.role != "restaurant_admin" || principal.restaurantId == null) {
            throw ForbiddenException("Only restaurant admins can view claims on a deal")
        }

        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT restaurant_id FROM deals WHERE id = ?").use { stmt ->
                stmt.setObject(1, dealId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw NotFoundException("Deal not found")
                    }
                    val restId = rs.getObject("restaurant_id") as UUID
                    if (restId != principal.restaurantId) {
                        throw NotFoundException("Deal not found")
                    }
                }
            }

            val sql = if (!statusFilter.isNullOrBlank()) {
                """
                    SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at
                    FROM claims
                    WHERE deal_id = ? AND status = ?
                    ORDER BY created_at DESC
                """.trimIndent()
            } else {
                """
                    SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at
                    FROM claims
                    WHERE deal_id = ?
                    ORDER BY created_at DESC
                """.trimIndent()
            }

            val list = mutableListOf<ClaimResponse>()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, dealId)
                if (!statusFilter.isNullOrBlank()) {
                    stmt.setString(2, statusFilter.trim().uppercase())
                }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        list.add(
                            ClaimResponse(
                                id = rs.getObject("id").toString(),
                                deal_id = rs.getObject("deal_id").toString(),
                                customer_id = rs.getObject("customer_id").toString(),
                                quantity = rs.getInt("quantity"),
                                amount_paise = rs.getInt("amount_paise"),
                                status = rs.getString("status"),
                                expires_at = rs.getTimestamp("expires_at").toInstant().toString()
                            )
                        )
                    }
                }
            }
            list
        }
    }
}
