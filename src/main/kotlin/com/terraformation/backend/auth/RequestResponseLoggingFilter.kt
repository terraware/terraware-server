package com.terraformation.backend.auth

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.log.perClassLogger
import io.ktor.http.ContentType
import io.ktor.http.charset
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.ws.rs.core.MediaType
import java.nio.charset.StandardCharsets
import kotlin.math.min
import org.slf4j.MDC
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Logs request and response bodies from users whose lowercase email addresses match a regular
 * expression. This filter should come after [TerrawareUserFilter] so that [CurrentUserHolder] is
 * initialized.
 *
 * Only JSON and HTML form payloads are logged, and only the first 10000 bytes of long payloads are
 * logged. Requests from device manager users are never logged.
 *
 * Payloads are logged using [MDC] rather than included in the message text so that they show up as
 * discrete values in structured logs without requiring extra parsing.
 */
class RequestResponseLoggingFilter(
    private val requestLogConfig: TerrawareServerConfig.RequestLogConfig
) : Filter {
  /** Log up to this many bytes of each payload. */
  private val maxPayloadSize = 10000

  private val log = perClassLogger()

  private val loggableContentTypes =
      listOf(MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED)

  override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    val user = CurrentUserHolder.getCurrentUser()
    if (log.isDebugEnabled &&
        requestLogConfig.emailRegex != null &&
        user is IndividualUser &&
        user.email.lowercase().matches(requestLogConfig.emailRegex) &&
        request is HttpServletRequest &&
        response is HttpServletResponse &&
        request.dispatcherType != DispatcherType.ASYNC &&
        (requestLogConfig.excludeRegex == null ||
            !request.requestURI.matches(requestLogConfig.excludeRegex))) {
      val wrappedRequest = ContentCachingRequestWrapper(request, maxPayloadSize)
      val wrappedResponse = ContentCachingResponseWrapper(response)

      try {
        chain.doFilter(wrappedRequest, wrappedResponse)
      } finally {
        val oldMdc = MDC.getCopyOfContextMap()
        try {
          mdcPut("request", payload(wrappedRequest.contentType, wrappedRequest.contentAsByteArray))
          mdcPut(
              "response", payload(wrappedResponse.contentType, wrappedResponse.contentAsByteArray))
          mdcPut("email", user.email)
          mdcPut("method", wrappedRequest.method)
          mdcPut("uri", wrappedRequest.requestURI)

          log.debug("Request")
        } finally {
          oldMdc?.let { MDC.setContextMap(it) } ?: MDC.clear()
          wrappedResponse.copyBodyToResponse()
        }
      }
    } else {
      chain.doFilter(request, response)
    }
  }

  private fun mdcPut(key: String, value: String?) {
    if (value != null) {
      MDC.put(key, value)
    }
  }

  /** Returns the string representation of the payload if the payload is loggable. */
  private fun payload(contentType: String?, bytes: ByteArray): String? {
    val parsedContentType = contentType?.let { ContentType.parse(it) } ?: return null

    return if (bytes.isNotEmpty() && loggableContentTypes.any { parsedContentType.match(it) }) {
      String(
          bytes,
          0,
          min(bytes.size, maxPayloadSize),
          parsedContentType.charset() ?: StandardCharsets.UTF_8)
    } else {
      null
    }
  }
}
