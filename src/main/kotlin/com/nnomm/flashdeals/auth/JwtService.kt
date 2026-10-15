package com.nnomm.flashdeals.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.flashdeals.config.AppProperties
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID

@Service
class JwtService(private val appProperties: AppProperties) {
    private val algorithm: Algorithm
        get() = Algorithm.HMAC256(appProperties.jwtSecret)

    fun verify(token: String): UserPrincipal {
        val decoded = try {
            JWT.decode(token)
        } catch (e: Exception) {
            throw AuthenticationException("Malformed JWT structure")
        }

        // Reject any algorithm other than HS256, including 'none'
        if (!"HS256".equals(decoded.algorithm, ignoreCase = false)) {
            throw AuthenticationException("Unsupported JWT algorithm: ${decoded.algorithm}")
        }

        val verifier = JWT.require(algorithm).build()
        val verifiedJwt = try {
            verifier.verify(decoded)
        } catch (e: Exception) {
            throw AuthenticationException("JWT verification failed: ${e.message}")
        }

        val subClaim = verifiedJwt.subject ?: throw AuthenticationException("Missing sub claim")
        val userId = try {
            UUID.fromString(subClaim)
        } catch (e: Exception) {
            throw AuthenticationException("sub claim must be a valid UUID")
        }

        val roleClaim = verifiedJwt.getClaim("role")?.asString()
            ?: throw AuthenticationException("Missing role claim")

        if (roleClaim != "customer" && roleClaim != "restaurant_admin") {
            throw AuthenticationException("Invalid role claim: $roleClaim")
        }

        val restaurantId = if (roleClaim == "restaurant_admin") {
            val restClaim = verifiedJwt.getClaim("restaurant_id")?.asString()
                ?: throw AuthenticationException("restaurant_admin token missing restaurant_id claim")
            try {
                UUID.fromString(restClaim)
            } catch (e: Exception) {
                throw AuthenticationException("restaurant_id claim must be a valid UUID")
            }
        } else {
            null
        }

        return UserPrincipal(
            userId = userId,
            role = roleClaim,
            restaurantId = restaurantId
        )
    }

    fun mintToken(
        userId: UUID,
        role: String,
        restaurantId: UUID? = null,
        expiresInSeconds: Long = 3600
    ): String {
        val now = Instant.now()
        val exp = now.plusSeconds(expiresInSeconds)

        var builder = JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", role)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(exp))

        if (restaurantId != null) {
            builder = builder.withClaim("restaurant_id", restaurantId.toString())
        }

        return builder.sign(algorithm)
    }
}

class AuthenticationException(message: String) : RuntimeException(message)
class ForbiddenException(message: String) : RuntimeException(message)
class NotFoundException(message: String) : RuntimeException(message)
class ConflictException(val code: String, message: String) : RuntimeException(message)
class ValidationException(message: String) : RuntimeException(message)
class IdempotencyKeyInvalidException(message: String) : RuntimeException(message)
class IdempotencyKeyReusedException(message: String) : RuntimeException(message)
class PayloadTooLargeException(message: String) : RuntimeException(message)
