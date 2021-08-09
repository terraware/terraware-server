package com.terraformation.backend.util

import java.net.http.HttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
  fun httpClient(): HttpClient {
    return HttpClient.newHttpClient()
  }
}
