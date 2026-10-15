package com.nnomm.flashdeals.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeals.auth.ConflictException
import com.nnomm.flashdeals.auth.ForbiddenException
import com.nnomm.flashdeals.auth.IdempotencyKeyInvalidException
import com.nnomm.flashdeals.auth.IdempotencyKeyReusedException
import com.nnomm.flashdeals.auth.NotFoundException
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.auth.ValidationException
import com.nnomm.flashdeals.config.AppProperties
import com.nnomm.flashdeals.models.ClaimRequest
import com.nnomm.flashdeals.models.ClaimResponse
import com.nnomm.flashdeals.models.ErrorDetails
import com.nnomm.flashdeals.models.ErrorResponse
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class ClaimExecutionResult(
    val statusCode: Int,
    val responseBody: String,
    val isReplayed: Boolean
)

@Service
class ClaimService(
    private val dataSource: DataSource,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {
    private val idempotencyKeyRegex = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun claimDeal(
        principal: UserPrincipal,
        dealId: UUID,
        idempotencyKeyHeader: String?,
        req: ClaimRequest
    ): ClaimExecutionResult {
        // 1. Role verification
        if (principal.role != "customer") {
            throw ForbiddenException("Only customers can claim deals")
        }

        // 2. Idempotency Key validation
        if (idempotencyKeyHeader.isNullOrBlank() || !idempotencyKeyRegex.matches(idempotencyKeyHeader)) {
            throw IdempotencyKeyInvalidException("Idempotency-Key header is missing or malformed")
        }

        // 3. Request validation
        val quantity = req.quantity
        if (quantity == null || quantity !in 1..3) {
            throw ValidationException("quantity must be between 1 and 3")
        }

        // 4. Verify deal existence (404 before storing idempotency)
        dataSource.connection.use { conn ->
            val sql = "SELECT id FROM deals WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, dealId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw NotFoundException("Deal not found")
                    }
                }
            }
        }

        val customerId = principal.userId
        val idempotencyKey = idempotencyKeyHeader

        // 5. Atomic idempotency coordination and claim processing
        return executeClaimWithIdempotency(customerId, idempotencyKey, dealId, quantity)
    }

    private fun executeClaimWithIdempotency(
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int
    ): ClaimExecutionResult {
        val maxWaitMs = 3000L
        val startWait = System.currentTimeMillis()

        while (true) {
            val stepResult: ClaimExecutionResult? = dataSource.connection.use connBlock@{ conn ->
                conn.autoCommit = false
                conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                try {
                    // Check existing idempotency record with row lock
                    val selectSql = """
                        SELECT customer_id, key, deal_id, quantity, status, response_status_code, response_body, locked_at, created_at
                        FROM idempotency_records
                        WHERE customer_id = ? AND key = ?
                        FOR UPDATE
                    """.trimIndent()

                    var existingRecordFound = false
                    var recordDealId: UUID? = null
                    var recordQuantity = 0
                    var recordStatus = ""
                    var recordStatusCode = 0
                    var recordBody: String? = null
                    var lockedAt: Instant = Instant.EPOCH

                    conn.prepareStatement(selectSql).use { stmt ->
                        stmt.setObject(1, customerId)
                        stmt.setString(2, key)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                existingRecordFound = true
                                recordDealId = rs.getObject("deal_id") as UUID
                                recordQuantity = rs.getInt("quantity")
                                recordStatus = rs.getString("status")
                                recordStatusCode = rs.getInt("response_status_code")
                                recordBody = rs.getString("response_body")
                                lockedAt = rs.getTimestamp("locked_at").toInstant()
                            }
                        }
                    }

                    if (existingRecordFound) {
                        // Key reused with different deal or quantity
                        if (recordDealId != dealId || recordQuantity != quantity) {
                            conn.rollback()
                            throw IdempotencyKeyReusedException("Idempotency key was previously used for a different deal or quantity")
                        }

                        // If completed, replay response
                        if (recordStatus == "COMPLETED") {
                            conn.commit()
                            return@connBlock ClaimExecutionResult(
                                statusCode = recordStatusCode,
                                responseBody = recordBody ?: "",
                                isReplayed = true
                            )
                        }

                        // If IN_PROGRESS
                        val ageSeconds = Duration.between(lockedAt, Instant.now()).seconds
                        if (ageSeconds > 60) {
                            // Previous process crashed! Reclaim lock and proceed
                            val updateLockSql = """
                                UPDATE idempotency_records
                                SET locked_at = NOW(), updated_at = NOW()
                                WHERE customer_id = ? AND key = ?
                            """.trimIndent()
                            conn.prepareStatement(updateLockSql).use { uStmt ->
                                uStmt.setObject(1, customerId)
                                uStmt.setString(2, key)
                                uStmt.executeUpdate()
                            }
                        } else {
                            conn.rollback()
                            return@connBlock null
                        }
                    } else {
                        // Insert IN_PROGRESS record atomically
                        val insertSql = """
                            INSERT INTO idempotency_records (customer_id, key, deal_id, quantity, status, locked_at, created_at, updated_at)
                            VALUES (?, ?, ?, ?, 'IN_PROGRESS', NOW(), NOW(), NOW())
                            ON CONFLICT (customer_id, key) DO NOTHING
                        """.trimIndent()
                        val inserted = conn.prepareStatement(insertSql).use { iStmt ->
                            iStmt.setObject(1, customerId)
                            iStmt.setString(2, key)
                            iStmt.setObject(3, dealId)
                            iStmt.setInt(4, quantity)
                            iStmt.executeUpdate()
                        }
                        if (inserted == 0) {
                            conn.rollback()
                            return@connBlock null
                        }
                    }

                    // Execute claim logic inside same transaction
                    val result = processClaimInsideTransaction(conn, customerId, key, dealId, quantity)
                    conn.commit()
                    result
                } catch (e: Throwable) {
                    conn.rollback()
                    throw e
                }
            }

            if (stepResult != null) {
                return stepResult
            }

            if (System.currentTimeMillis() - startWait >= maxWaitMs) {
                throw ConflictException("REQUEST_IN_PROGRESS", "A request with the same key is still running")
            }

            Thread.sleep(100)
        }
    }

    private fun processClaimInsideTransaction(
        conn: Connection,
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int
    ): ClaimExecutionResult {
        // Lock deal row to serialize stock reservation
        val dealSql = """
            SELECT id, price_paise, stock_available, starts_at, ends_at
            FROM deals
            WHERE id = ?
            FOR UPDATE
        """.trimIndent()

        var pricePaise = 0
        var stockAvailable = 0
        var startsAt: Instant = Instant.EPOCH
        var endsAt: Instant = Instant.EPOCH

        conn.prepareStatement(dealSql).use { stmt ->
            stmt.setObject(1, dealId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw NotFoundException("Deal not found")
                }
                pricePaise = rs.getInt("price_paise")
                stockAvailable = rs.getInt("stock_available")
                startsAt = rs.getTimestamp("starts_at").toInstant()
                endsAt = rs.getTimestamp("ends_at").toInstant()
            }
        }

        val now = Instant.now()

        // 1. Check deal live window
        if (now.isBefore(startsAt) || !now.isBefore(endsAt)) {
            val errorBody = objectMapper.writeValueAsString(
                ErrorResponse(ErrorDetails("DEAL_NOT_LIVE", "The deal hasn't started yet, or has ended"))
            )
            saveIdempotencyOutcome(conn, customerId, key, 409, errorBody)
            return ClaimExecutionResult(409, errorBody, isReplayed = false)
        }

        // 2. Check active claims for customer on this deal: RESERVED, PAID, NEEDS_REVIEW
        val activeClaimsSql = """
            SELECT id, status, quantity, expires_at
            FROM claims
            WHERE customer_id = ? AND deal_id = ? AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
            FOR UPDATE
        """.trimIndent()

        var hasActiveClaim = false
        conn.prepareStatement(activeClaimsSql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setObject(2, dealId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val cId = rs.getObject("id") as UUID
                    val cStatus = rs.getString("status")
                    val cQty = rs.getInt("quantity")
                    val cExpiresAt = rs.getTimestamp("expires_at").toInstant()

                    if (cStatus == "RESERVED" && (now.isAfter(cExpiresAt) || now == cExpiresAt)) {
                        conn.prepareStatement("UPDATE claims SET status = 'EXPIRED', updated_at = NOW() WHERE id = ?").use { uStmt ->
                            uStmt.setObject(1, cId)
                            uStmt.executeUpdate()
                        }
                        conn.prepareStatement("UPDATE deals SET stock_available = stock_available + ? WHERE id = ?").use { dStmt ->
                            dStmt.setInt(1, cQty)
                            dStmt.setObject(2, dealId)
                            dStmt.executeUpdate()
                        }
                        stockAvailable += cQty
                    } else {
                        hasActiveClaim = true
                    }
                }
            }
        }

        if (hasActiveClaim) {
            val errorBody = objectMapper.writeValueAsString(
                ErrorResponse(ErrorDetails("ALREADY_CLAIMED", "The customer already holds an active claim on this deal"))
            )
            saveIdempotencyOutcome(conn, customerId, key, 409, errorBody)
            return ClaimExecutionResult(409, errorBody, isReplayed = false)
        }

        // 3. Check stock availability
        if (stockAvailable < quantity) {
            val errorBody = objectMapper.writeValueAsString(
                ErrorResponse(ErrorDetails("SOLD_OUT", "Not enough units left for this quantity"))
            )
            saveIdempotencyOutcome(conn, customerId, key, 409, errorBody)
            return ClaimExecutionResult(409, errorBody, isReplayed = false)
        }

        // 4. Reserve stock and create claim
        val updateStockSql = "UPDATE deals SET stock_available = stock_available - ? WHERE id = ?"
        conn.prepareStatement(updateStockSql).use { stmt ->
            stmt.setInt(1, quantity)
            stmt.setObject(2, dealId)
            stmt.executeUpdate()
        }

        val claimId = UUID.randomUUID()
        val amountPaise = quantity * pricePaise
        val expiresAt = now.plusSeconds(appProperties.claimTtlSeconds)

        val insertClaimSql = """
            INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, NOW(), NOW())
        """.trimIndent()

        conn.prepareStatement(insertClaimSql).use { stmt ->
            stmt.setObject(1, claimId)
            stmt.setObject(2, dealId)
            stmt.setObject(3, customerId)
            stmt.setInt(4, quantity)
            stmt.setInt(5, amountPaise)
            stmt.setTimestamp(6, Timestamp.from(expiresAt))
            stmt.executeUpdate()
        }

        val claimResponse = ClaimResponse(
            id = claimId.toString(),
            deal_id = dealId.toString(),
            customer_id = customerId.toString(),
            quantity = quantity,
            amount_paise = amountPaise,
            status = "RESERVED",
            expires_at = expiresAt.toString()
        )

        val successBody = objectMapper.writeValueAsString(claimResponse)
        saveIdempotencyOutcome(conn, customerId, key, 201, successBody)

        return ClaimExecutionResult(201, successBody, isReplayed = false)
    }

    private fun saveIdempotencyOutcome(
        conn: Connection,
        customerId: UUID,
        key: String,
        statusCode: Int,
        responseBody: String
    ) {
        val sql = """
            UPDATE idempotency_records
            SET status = 'COMPLETED',
                response_status_code = ?,
                response_body = ?,
                updated_at = NOW()
            WHERE customer_id = ? AND key = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, statusCode)
            stmt.setString(2, responseBody)
            stmt.setObject(3, customerId)
            stmt.setString(4, key)
            stmt.executeUpdate()
        }
    }

    fun getClaim(principal: UserPrincipal, claimId: UUID): ClaimResponse {
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
                val sql = """
                    SELECT c.id, c.deal_id, c.customer_id, c.quantity, c.amount_paise, c.status, c.expires_at, d.restaurant_id
                    FROM claims c
                    JOIN deals d ON c.deal_id = d.id
                    WHERE c.id = ?
                    FOR UPDATE
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, claimId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            throw NotFoundException("Claim not found")
                        }

                        val claimCustomerId = rs.getObject("customer_id") as UUID
                        val dealRestaurantId = rs.getObject("restaurant_id") as UUID

                        val isOwnerCustomer = principal.userId == claimCustomerId
                        val isDealAdmin = principal.role == "restaurant_admin" && principal.restaurantId == dealRestaurantId

                        if (!isOwnerCustomer && !isDealAdmin) {
                            throw NotFoundException("Claim not found")
                        }

                        var status = rs.getString("status")
                        val quantity = rs.getInt("quantity")
                        val dealId = rs.getObject("deal_id") as UUID
                        val expiresAt = rs.getTimestamp("expires_at").toInstant()
                        val now = Instant.now()

                        if (status == "RESERVED" && (now.isAfter(expiresAt) || now == expiresAt)) {
                            status = "EXPIRED"
                            conn.prepareStatement("UPDATE claims SET status = 'EXPIRED', updated_at = NOW() WHERE id = ?").use { uStmt ->
                                uStmt.setObject(1, claimId)
                                uStmt.executeUpdate()
                            }
                            conn.prepareStatement("UPDATE deals SET stock_available = stock_available + ? WHERE id = ?").use { dStmt ->
                                dStmt.setInt(1, quantity)
                                dStmt.setObject(2, dealId)
                                dStmt.executeUpdate()
                            }
                        }

                        conn.commit()

                        ClaimResponse(
                            id = rs.getObject("id").toString(),
                            deal_id = dealId.toString(),
                            customer_id = claimCustomerId.toString(),
                            quantity = quantity,
                            amount_paise = rs.getInt("amount_paise"),
                            status = status,
                            expires_at = expiresAt.toString()
                        )
                    }
                }
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }
}
