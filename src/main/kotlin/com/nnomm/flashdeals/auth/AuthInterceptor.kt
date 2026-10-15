package com.nnomm.flashdeals.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class AuthInterceptor(private val jwtService: JwtService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val uri = request.requestURI
        val method = request.method

        if (method == "OPTIONS") {
            return true
        }

        // Public endpoints
        if (uri.startsWith("/v1/webhooks/") || uri.startsWith("/actuator/") || uri == "/metrics") {
            return true
        }

        val authHeader = request.getHeader("Authorization")
            ?: throw AuthenticationException("Missing Authorization header")

        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
            throw AuthenticationException("Authorization header must use Bearer scheme")
        }

        val token = authHeader.substring(7).trim()
        if (token.isEmpty()) {
            throw AuthenticationException("Bearer token is empty")
        }

        val principal = jwtService.verify(token)
        request.setAttribute("principal", principal)
        return true
    }
}
