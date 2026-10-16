package com.nnomm.flashdeals.service

import com.nnomm.flashdeals.auth.ValidationException
import com.nnomm.flashdeals.models.PaymentWebhookRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.sql.DataSource

@Service
class PaymentWebhookService(private val dataSource: DataSource) {
    private val log = LoggerFactory.getLogger(PaymentWebhookService::class.java)

    fun processWebhook(req: PaymentWebhookRequest) {
        val eventId = req.event_id?.trim()
        if (eventId.isNullOrEmpty()) {
            throw ValidationException("event_id is required")
        }

        val type = req.type?.trim()
        if (type != "payment.succeeded" && type != "payment.failed") {
            throw ValidationException("type must be payment.succeeded or payment.failed")
        }

        val claimIdStr = req.claim_id?.trim()
        if (claimIdStr.isNullOrEmpty()) {
            throw ValidationException("claim_id is required")
        }

        val claimId = try {
            UUID.fromString(claimIdStr)
        } catch (e: Exception) {
            throw ValidationException("claim_id must be a valid UUID")
        }

        val amountPaise = req.amount_paise
            ?: throw ValidationException("amount_paise is required")

        val occurredAt = try {
            Instant.parse(req.occurred_at ?: "")
        } catch (e: DateTimeParseException) {
            throw ValidationException("occurred_at must be an ISO 8601 UTC timestamp")
        }

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
                // 1. Idempotency check: has this event_id already been processed?
                val checkEventSql = "SELECT event_id FROM payment_events WHERE event_id = ? FOR UPDATE"
                conn.prepareStatement(checkEventSql).use { stmt ->
                    stmt.setString(1, eventId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            log.info("Duplicate webhook event_id {} ignored", eventId)
                            conn.rollback()
                            return
                        }
                    }
                }

                // Record this event
                val insertEventSql = """
                    INSERT INTO payment_events (event_id, claim_id, type, amount_paise, occurred_at, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                """.trimIndent()
                conn.prepareStatement(insertEventSql).use { stmt ->
                    stmt.setString(1, eventId)
                    stmt.setObject(2, claimId)
                    stmt.setString(3, type)
                    stmt.setInt(4, amountPaise)
                    stmt.setTimestamp(5, Timestamp.from(occurredAt))
                    stmt.executeUpdate()
                }

                // 2. Lock and retrieve claim
                val selectClaimSql = """
                    SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at
                    FROM claims
                    WHERE id = ?
                    FOR UPDATE
                """.trimIndent()

                var foundClaim = false
                var currentStatus = ""
                var expectedAmountPaise = 0
                var expiresAt: Instant = Instant.EPOCH
                var quantity = 0
                var dealId: UUID? = null

                conn.prepareStatement(selectClaimSql).use { stmt ->
                    stmt.setObject(1, claimId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            foundClaim = true
                            currentStatus = rs.getString("status")
                            expectedAmountPaise = rs.getInt("amount_paise")
                            expiresAt = rs.getTimestamp("expires_at").toInstant()
                            quantity = rs.getInt("quantity")
                            dealId = rs.getObject("deal_id") as UUID
                        }
                    }
                }

                if (!foundClaim) {
                    log.warn("Webhook received for unknown claim_id: {}. Acknowledged without state change.", claimId)
                    conn.commit()
                    return
                }

                if (type == "payment.failed") {
                    log.info("Payment failed event recorded for claim {}", claimId)
                    conn.commit()
                    return
                }

                val now = Instant.now()
                val isExpired = now.isAfter(expiresAt) || now == expiresAt

                when (currentStatus) {
                    "RESERVED" -> {
                        if (!isExpired) {
                            if (amountPaise == expectedAmountPaise) {
                                updateClaimStatus(conn, claimId, "PAID")
                                log.info("Claim {} marked PAID", claimId)
                            } else {
                                updateClaimStatus(conn, claimId, "NEEDS_REVIEW")
                                log.warn("Claim {} marked NEEDS_REVIEW: expected {}, got {}", claimId, expectedAmountPaise, amountPaise)
                            }
                        } else {
                            updateClaimStatus(conn, claimId, "REFUND_PENDING")
                            dealId?.let { dId ->
                                conn.prepareStatement("UPDATE deals SET stock_available = stock_available + ? WHERE id = ?").use { dStmt ->
                                    dStmt.setInt(1, quantity)
                                    dStmt.setObject(2, dId)
                                    dStmt.executeUpdate()
                                }
                            }
                            log.info("Late payment for expired RESERVED claim {}. Marked REFUND_PENDING and released stock.", claimId)
                        }
                    }
                    "EXPIRED" -> {
                        updateClaimStatus(conn, claimId, "REFUND_PENDING")
                        log.info("Late payment for EXPIRED claim {}. Marked REFUND_PENDING; units remain in stock.", claimId)
                    }
                    "PAID", "NEEDS_REVIEW", "REFUND_PENDING" -> {
                        log.warn("Additional payment.succeeded event {} for claim {} already in status {}. Ignored state change.",
                            eventId, claimId, currentStatus)
                    }
                }

                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    private fun updateClaimStatus(conn: Connection, claimId: UUID, status: String) {
        val sql = "UPDATE claims SET status = ?, updated_at = NOW() WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, status)
            stmt.setObject(2, claimId)
            stmt.executeUpdate()
        }
    }
}
