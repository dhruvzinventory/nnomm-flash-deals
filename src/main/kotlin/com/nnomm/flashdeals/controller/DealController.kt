package com.nnomm.flashdeals.controller

import com.nnomm.flashdeals.auth.CurrentUser
import com.nnomm.flashdeals.auth.UserPrincipal
import com.nnomm.flashdeals.auth.ValidationException
import com.nnomm.flashdeals.models.ClaimResponse
import com.nnomm.flashdeals.models.CreateDealRequest
import com.nnomm.flashdeals.models.DealResponse
import com.nnomm.flashdeals.service.DealService
import com.nnomm.flashdeals.service.StretchService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/deals")
class DealController(
    private val dealService: DealService,
    private val stretchService: StretchService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createDeal(
        @CurrentUser principal: UserPrincipal,
        @RequestBody req: CreateDealRequest
    ): DealResponse {
        return dealService.createDeal(principal, req)
    }

    @GetMapping("/{dealId}")
    fun getDeal(@PathVariable dealId: String): DealResponse {
        val uuid = try {
            UUID.fromString(dealId)
        } catch (e: Exception) {
            throw ValidationException("dealId must be a valid UUID")
        }
        return dealService.getDeal(uuid)
    }

    @GetMapping("/{dealId}/claims")
    fun getDealClaims(
        @CurrentUser principal: UserPrincipal,
        @PathVariable dealId: String,
        @RequestParam(required = false) status: String?
    ): List<ClaimResponse> {
        val uuid = try {
            UUID.fromString(dealId)
        } catch (e: Exception) {
            throw ValidationException("dealId must be a valid UUID")
        }
        return stretchService.getDealClaimsForAdmin(principal, uuid, status)
    }
}
