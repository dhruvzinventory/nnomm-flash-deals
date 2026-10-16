package com.nnomm.flashdeals.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

@Component
class ClaimExpiryWorker(private val dataSource: DataSource) {
    private val log = LoggerFactory.getLogger(ClaimExpiryWorker::class.java)

    @Scheduled(fixedDelay = 1000)
    fun scheduledExpiry() {
        try {
            processExpiredClaimsBatch()
        } catch (e: Throwable) {
            log.error("Error in scheduled ClaimExpiryWorker loop", e)
        }
    }

    /**
     * Finds and expires claims whose expires_at is in the past.
     * Uses 'FOR UPDATE SKIP LOCKED' so multiple running instances of the service
     * never conflict or block each other.
     */
    fun processExpiredClaimsBatch(batchSize: Int = 50): Int {
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
                val selectSql = """
                    SELECT id, deal_id, quantity
                    FROM claims
                    WHERE status = 'RESERVED' AND expires_at <= NOW()
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                """.trimIndent()

                data class ExpiredClaim(val id: UUID, val dealId: UUID, val quantity: Int)
                val toExpire = mutableListOf<ExpiredClaim>()

                conn.prepareStatement(selectSql).use { stmt ->
                    stmt.setInt(1, batchSize)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            toExpire.add(
                                ExpiredClaim(
                                    id = rs.getObject("id") as UUID,
                                    dealId = rs.getObject("deal_id") as UUID,
                                    quantity = rs.getInt("quantity")
                                )
                            )
                        }
                    }
                }

                if (toExpire.isEmpty()) {
                    conn.commit()
                    return@use 0
                }

                val updateClaimSql = "UPDATE claims SET status = 'EXPIRED', updated_at = NOW() WHERE id = ? AND status = 'RESERVED'"
                val updateDealSql = "UPDATE deals SET stock_available = stock_available + ? WHERE id = ?"

                conn.prepareStatement(updateClaimSql).use { claimStmt ->
                    conn.prepareStatement(updateDealSql).use { dealStmt ->
                        for (claim in toExpire) {
                            claimStmt.setObject(1, claim.id)
                            val updated = claimStmt.executeUpdate()
                            if (updated > 0) {
                                dealStmt.setInt(1, claim.quantity)
                                dealStmt.setObject(2, claim.dealId)
                                dealStmt.executeUpdate()
                                log.debug("Expired claim {} and returned {} units to deal {}", claim.id, claim.quantity, claim.dealId)
                            }
                        }
                    }
                }

                conn.commit()
                toExpire.size
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }
}
