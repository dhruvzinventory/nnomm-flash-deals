package com.nnomm.flashdeals.controller

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class MetricsController(private val registry: PrometheusMeterRegistry) {

    @GetMapping("/metrics", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun scrapeMetrics(): String {
        return registry.scrape()
    }
}
