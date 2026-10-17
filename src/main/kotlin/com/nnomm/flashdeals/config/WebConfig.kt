package com.nnomm.flashdeals.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeals.auth.AuthInterceptor
import com.nnomm.flashdeals.auth.CurrentUserArgumentResolver
import com.nnomm.flashdeals.models.ErrorDetails
import com.nnomm.flashdeals.models.ErrorResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val authInterceptor: AuthInterceptor,
    private val currentUserArgumentResolver: CurrentUserArgumentResolver,
    private val objectMapper: ObjectMapper
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/v1/webhooks/**", "/actuator/**", "/metrics")
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserArgumentResolver)
    }

    @Bean
    fun prometheusMeterRegistry(): io.micrometer.prometheusmetrics.PrometheusMeterRegistry {
        return io.micrometer.prometheusmetrics.PrometheusMeterRegistry(io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT)
    }

    @Bean
    fun payloadSizeFilterRegistration(): FilterRegistrationBean<PayloadSizeFilter> {
        val registration = FilterRegistrationBean<PayloadSizeFilter>()
        registration.filter = PayloadSizeFilter(objectMapper)
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }
}

class PayloadSizeFilter(private val objectMapper: ObjectMapper) : OncePerRequestFilter() {
    private val maxBytes = 16 * 1024 // 16 KB

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val contentLength = request.contentLengthLong
        if (contentLength > maxBytes) {
            response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE // 413
            response.contentType = "application/json"
            val error = ErrorResponse(ErrorDetails("PAYLOAD_TOO_LARGE", "The body is over 16 KB"))
            response.writer.write(objectMapper.writeValueAsString(error))
            return
        }
        filterChain.doFilter(request, response)
    }
}
