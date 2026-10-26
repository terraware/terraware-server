package com.terraformation.backend.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.jackson3.JacksonConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * Configures the HTTP client.
 *
 * For now, this just uses the default configuration including the default connection and thread
 * pools. It is published as a Spring bean both so that it can be shared across services and so that
 * we can change the configuration centrally in the future, e.g., to adjust pool sizes.
 */
@Configuration
class HttpClientConfig {
  @Bean
  fun ktorEngine(): HttpClientEngine {
    return Java.create()
  }

  @Bean
  fun httpClient(engine: HttpClientEngine, objectMapper: ObjectMapper): HttpClient {
    return HttpClient(engine) {
      defaultRequest { contentType(ContentType.Application.Json) }

      // By default, treat non-2xx responses as errors. This can be overridden per request.
      expectSuccess = true

      install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(objectMapper))
      }
    }
  }
}
