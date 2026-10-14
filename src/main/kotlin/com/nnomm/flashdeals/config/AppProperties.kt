package com.nnomm.flashdeals.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "app")
class AppProperties {
    var jwtSecret: String = "super-secret-jwt-key-for-local-testing-purposes-123"
    var webhookSecret: String = "super-secret-webhook-key-for-local-testing-123"
    var claimTtlSeconds: Long = 600L
}
