package com.terraformation.backend.auth

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.log.perClassLogger
import io.ktor.http.ContentType
import io.ktor.http.charset
import java.nio.charset.StandardCharsets
import javax.servlet.DispatcherType
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.ws.rs.core.MediaType
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
class RequestResponseLoggingFilter(private val requestLogEmailRegex: Regex) : Filter {
  /** Log up to this many bytes of each payload. */
  private val maxPayloadSize = 10000

  private val log = perClassLogger()

  private val loggableContentTypes =
      listOf(MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED)

  override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    val user = CurrentUserHolder.getCurrentUser()
    if (log.isDebugEnabled &&
        user is IndividualUser &&
        user.email.lowercase().matches(requestLogEmailRegex) &&
        request is HttpServletRequest &&
        response is HttpServletResponse &&
        request.dispatcherType != DispatcherType.ASYNC) {
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
