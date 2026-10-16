package com.nnomm.flashdeals.controller

import com.nnomm.flashdeals.auth.CurrentUser
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.auth.ValidationException
import com.nnomm.flashdeals.models.ClaimRequest
import com.nnomm.flashdeals.models.ClaimResponse
import com.nnomm.flashdeals.models.PaginatedClaimsResponse
import com.nnomm.flashdeals.service.ClaimService
import com.nnomm.flashdeals.service.StretchService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ClaimController(
    private val claimService: ClaimService,
    private val stretchService: StretchService
) {

    @PostMapping("/v1/deals/{dealId}/claims")
    fun claimDeal(
        @CurrentUser principal: UserPrincipal,
        @PathVariable dealId: String,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody req: ClaimRequest
    ): ResponseEntity<String> {
        val uuid = try {
            UUID.fromString(dealId)
        } catch (e: Exception) {
            throw ValidationException("dealId must be a valid UUID")
        }

        stretchService.checkAndRecordRateLimit(principal.userId, maxPerMinute = 200)

        val result = claimService.claimDeal(principal, uuid, idempotencyKey, req)
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        if (result.isReplayed) {
            headers.set("Idempotent-Replayed", "true")
        }

        return ResponseEntity
            .status(HttpStatus.valueOf(result.statusCode))
            .headers(headers)
            .body(result.responseBody)
    }

    @GetMapping("/v1/claims/{claimId}")
    fun getClaim(
        @CurrentUser principal: UserPrincipal,
        @PathVariable claimId: String
    ): ClaimResponse {
        val uuid = try {
            UUID.fromString(claimId)
        } catch (e: Exception) {
            throw ValidationException("claimId must be a valid UUID")
        }
        return claimService.getClaim(principal, uuid)
    }

    @GetMapping("/v1/me/claims")
    fun getMyClaims(
        @CurrentUser principal: UserPrincipal,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): PaginatedClaimsResponse {
        return stretchService.getCustomerClaims(principal, cursor, limit)
    }
}
