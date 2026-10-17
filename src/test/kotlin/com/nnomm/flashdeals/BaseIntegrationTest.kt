package com.nnomm.flashdeals

import com.nnomm.flashdeals.auth.JwtService
import com.nnomm.flashdeals.config.AppProperties
import com.nnomm.flashdeals.service.ClaimExpiryWorker
import com.nnomm.flashdeals.service.ClaimService
import com.nnomm.flashdeals.service.DealService
import com.nnomm.flashdeals.service.PaymentWebhookService
import com.nnomm.flashdeals.service.StretchService
import com.nnomm.flashdeals.webhook.WebhookVerifier
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@SpringBootTest
abstract class BaseIntegrationTest {

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jwtService: JwtService

    @Autowired
    lateinit var webhookVerifier: WebhookVerifier

    @Autowired
    lateinit var dealService: DealService

    @Autowired
    lateinit var claimService: ClaimService

    @Autowired
    lateinit var paymentWebhookService: PaymentWebhookService

    @Autowired
    lateinit var stretchService: StretchService

    @Autowired
    lateinit var expiryWorker: ClaimExpiryWorker

    @Autowired
    lateinit var appProperties: AppProperties

    val jwtSecret: String get() = appProperties.jwtSecret
    val webhookSecret: String get() = appProperties.webhookSecret

    inner class DbHelper {
        fun <T> withConnection(block: (java.sql.Connection) -> T): T = dataSource.connection.use(block)
        fun <T> withTransaction(block: (java.sql.Connection) -> T): T = dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val res = block(conn)
                conn.commit()
                res
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    val db get() = DbHelper()

    @BeforeEach
    fun cleanDatabase() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("TRUNCATE claims, idempotency_records, payment_events, claim_rate_limits, deals CASCADE").use {
                it.executeUpdate()
            }
        }
    }

    fun mintCustomerToken(customerId: UUID = UUID.randomUUID()): String {
        return jwtService.mintToken(userId = customerId, role = "customer")
    }

    fun mintAdminToken(restaurantId: UUID = UUID.randomUUID(), adminId: UUID = UUID.randomUUID()): String {
        return jwtService.mintToken(userId = adminId, role = "restaurant_admin", restaurantId = restaurantId)
    }

    fun generateWebhookSignature(
        rawBody: String,
        secret: String = appProperties.webhookSecret,
        timestamp: Long = Instant.now().epochSecond
    ): String {
        val message = "$timestamp.".toByteArray(Charsets.UTF_8) + rawBody.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(message).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$hash"
    }
}
