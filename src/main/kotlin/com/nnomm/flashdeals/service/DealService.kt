package com.nnomm.flashdeals.service

import com.nnomm.flashdeals.auth.ForbiddenException
import com.nnomm.flashdeals.auth.NotFoundException
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.auth.ValidationException
import com.nnomm.flashdeals.models.CreateDealRequest
import com.nnomm.flashdeals.models.DealResponse
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.sql.DataSource

@Service
class DealService(private val dataSource: DataSource) {

    fun createDeal(principal: UserPrincipal, req: CreateDealRequest): DealResponse {
        if (principal.role != "restaurant_admin" || principal.restaurantId == null) {
            throw ForbiddenException("Only restaurant admins can create deals")
        }

        val title = req.title?.trim()
        if (title.isNullOrEmpty() || title.length > 100) {
            throw ValidationException("title must be between 1 and 100 characters")
        }

        val pricePaise = req.price_paise
        if (pricePaise == null || pricePaise < 1 || pricePaise > 1_000_000) {
            throw ValidationException("price_paise must be between 1 and 1,000,000")
        }

        val stockTotal = req.stock_total
        if (stockTotal == null || stockTotal < 1 || stockTotal > 10_000) {
            throw ValidationException("stock_total must be between 1 and 10,000")
        }

        val startsAtStr = req.starts_at ?: throw ValidationException("starts_at is required")
        val endsAtStr = req.ends_at ?: throw ValidationException("ends_at is required")

        val startsAt = try {
            Instant.parse(startsAtStr)
        } catch (e: DateTimeParseException) {
            throw ValidationException("starts_at must be an ISO 8601 UTC timestamp")
        }

        val endsAt = try {
            Instant.parse(endsAtStr)
        } catch (e: DateTimeParseException) {
            throw ValidationException("ends_at must be an ISO 8601 UTC timestamp")
        }

        if (!startsAt.isBefore(endsAt)) {
            throw ValidationException("starts_at must be before ends_at")
        }

        val dealId = UUID.randomUUID()
        val restaurantId = principal.restaurantId

        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT INTO deals (id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, dealId)
                    stmt.setObject(2, restaurantId)
                    stmt.setString(3, title)
                    stmt.setInt(4, pricePaise)
                    stmt.setInt(5, stockTotal)
                    stmt.setInt(6, stockTotal)
                    stmt.setTimestamp(7, Timestamp.from(startsAt))
                    stmt.setTimestamp(8, Timestamp.from(endsAt))
                    stmt.executeUpdate()
                }

                conn.commit()

                DealResponse(
                    id = dealId.toString(),
                    restaurant_id = restaurantId.toString(),
                    title = title,
                    price_paise = pricePaise,
                    stock_total = stockTotal,
                    stock_available = stockTotal,
                    starts_at = startsAt.toString(),
                    ends_at = endsAt.toString()
                )
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    fun getDeal(dealId: UUID): DealResponse {
        return dataSource.connection.use { conn ->
            val sql = """
                SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at
                FROM deals
                WHERE id = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, dealId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw NotFoundException("Deal not found")
                    }
                    mapRowToDeal(rs)
                }
            }
        }
    }

    private fun mapRowToDeal(rs: ResultSet): DealResponse {
        val startsAt = rs.getTimestamp("starts_at").toInstant().toString()
        val endsAt = rs.getTimestamp("ends_at").toInstant().toString()

        return DealResponse(
            id = rs.getObject("id").toString(),
            restaurant_id = rs.getObject("restaurant_id").toString(),
            title = rs.getString("title"),
            price_paise = rs.getInt("price_paise"),
            stock_total = rs.getInt("stock_total"),
            stock_available = rs.getInt("stock_available"),
            starts_at = startsAt,
            ends_at = endsAt
        )
    }
}
