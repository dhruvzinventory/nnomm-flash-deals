package com.nnomm.flashdeals.webhook

import com.nnomm.flashdeals.auth.AuthenticationException
import com.nnomm.flashdeals.config.AppProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

@Component
class WebhookVerifier(private val appProperties: AppProperties) {

    fun verify(signatureHeader: String?, rawBodyBytes: ByteArray) {
        if (signatureHeader.isNullOrBlank()) {
            throw AuthenticationException("Missing X-Payment-Signature header")
        }

        val parts = signatureHeader.split(",")
        var timestampStr: String? = null
        var signatureHex: String? = null

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("t=")) {
                timestampStr = trimmed.substring(2)
            } else if (trimmed.startsWith("v1=")) {
                signatureHex = trimmed.substring(3)
            }
        }

        if (timestampStr == null || signatureHex == null) {
            throw AuthenticationException("Malformed X-Payment-Signature header: expected t=... and v1=...")
        }

        val timestampSeconds = timestampStr.toLongOrNull()
            ?: throw AuthenticationException("Invalid timestamp in signature header")

        val nowSeconds = Instant.now().epochSecond
        if (abs(nowSeconds - timestampSeconds) > 300) {
            throw AuthenticationException("Webhook signature timestamp expired or too far in future (> 300s drift)")
        }

        // Signed message: "$t." concatenated with raw request body bytes
        val prefix = "$timestampStr.".toByteArray(Charsets.UTF_8)
        val signedMessage = prefix + rawBodyBytes

        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(appProperties.webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val expectedBytes = mac.doFinal(signedMessage)
        val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }

        // Constant time comparison to prevent timing attacks
        val expectedHexBytes = expectedHex.toByteArray(Charsets.UTF_8)
        val providedHexBytes = signatureHex.lowercase().toByteArray(Charsets.UTF_8)

        if (!MessageDigest.isEqual(expectedHexBytes, providedHexBytes)) {
            throw AuthenticationException("Invalid webhook signature")
        }
    }
}
