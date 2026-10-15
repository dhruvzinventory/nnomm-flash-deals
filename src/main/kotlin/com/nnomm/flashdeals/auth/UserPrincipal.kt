package com.nnomm.flashdeals.auth

import java.util.UUID

data class UserPrincipal(
    val userId: UUID,
    val role: String, // "customer" or "restaurant_admin"
    val restaurantId: UUID? = null
)
