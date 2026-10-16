package com.nnomm.flashdeals.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeals.auth.ValidationException
import com.nnomm.flashdeals.models.PaymentWebhookRequest
import com.nnomm.flashdeals.service.PaymentWebhookService
import com.nnomm.flashdeals.webhook.WebhookVerifier
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/webhooks")
class WebhookController(
    private val webhookVerifier: WebhookVerifier,
    private val paymentWebhookService: PaymentWebhookService,
    private val objectMapper: ObjectMapper
) {

    @PostMapping("/payments")
    fun handlePaymentWebhook(
        @RequestHeader(value = "X-Payment-Signature", required = false) signatureHeader: String?,
        @RequestBody rawBodyBytes: ByteArray
    ): Map<String, String> {
        webhookVerifier.verify(signatureHeader, rawBodyBytes)

        val req = try {
            objectMapper.readValue(rawBodyBytes, PaymentWebhookRequest::class.java)
        } catch (e: Exception) {
            throw ValidationException("Invalid webhook payload JSON")
        }

        paymentWebhookService.processWebhook(req)
        return mapOf("status" to "ok")
    }
}
