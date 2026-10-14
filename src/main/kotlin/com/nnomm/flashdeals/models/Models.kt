package com.nnomm.flashdeals.models

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateDealRequest(
    val title: String? = null,
    val price_paise: Int? = null,
    val stock_total: Int? = null,
    val starts_at: String? = null,
    val ends_at: String? = null
)

data class DealResponse(
    val id: String,
    val restaurant_id: String,
    val title: String,
    val price_paise: Int,
    val stock_total: Int,
    val stock_available: Int,
    val starts_at: String,
    val ends_at: String
)

data class ClaimRequest(
    val quantity: Int? = null
)

data class ClaimResponse(
    val id: String,
    val deal_id: String,
    val customer_id: String,
    val quantity: Int,
    val amount_paise: Int,
    val status: String,
    val expires_at: String
)

data class PaymentWebhookRequest(
    val event_id: String? = null,
    val type: String? = null,
    val claim_id: String? = null,
    val amount_paise: Int? = null,
    val occurred_at: String? = null
)

data class ErrorDetails(
    val code: String,
    val message: String
)

data class ErrorResponse(
    val error: ErrorDetails
)

data class PaginatedClaimsResponse(
    val claims: List<ClaimResponse>,
    val next_cursor: String?,
    val has_more: Boolean
)

enum class ClaimStatus {
    RESERVED,
    PAID,
    EXPIRED,
    NEEDS_REVIEW,
    REFUND_PENDING
}
