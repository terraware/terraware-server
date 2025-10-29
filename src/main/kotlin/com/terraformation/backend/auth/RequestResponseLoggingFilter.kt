package com.terraformation.backend.auth

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.FunderUser
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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.min
import org.apache.commons.codec.binary.Base32
import org.slf4j.MDC
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Logs request and response bodies from users whose lowercase email addresses match a regular
 * expression, as well as request bodies for requests that fail. This filter should come after
 * [TerrawareUserFilter] so that [CurrentUserHolder] is initialized.
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

  /**
   * Prefix to include in request IDs. [ServletRequest.getRequestId] doesn't return globally unique
   * request IDs, and it's useful to be able to search for logs from a specific request.
   */
  private val requestIdPrefix: String by lazy {
    val uuid = UUID.randomUUID()
    val buffer = ByteBuffer.allocate(16)

    buffer.putLong(uuid.mostSignificantBits)
    buffer.putLong(uuid.leastSignificantBits)

    Base32().encodeToString(buffer.array()).trimEnd('=')
  }

  override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    val user = CurrentUserHolder.getCurrentUser()
    val oldMdc = MDC.getCopyOfContextMap()
    val requestId = "${requestIdPrefix}_${request.requestId}"

    try {
      mdcPut("authId", user?.authId)
      mdcPut("requestId", requestId)
      request.setAttribute("terrawareRequestId", requestId)

      if (user is IndividualUser || user is FunderUser) {
        mdcPut("email", user.email)
        request.setAttribute("terrawareEmail", user.email)

        if (
            log.isDebugEnabled &&
                request is HttpServletRequest &&
                response is HttpServletResponse &&
                request.dispatcherType != DispatcherType.ASYNC &&
                (requestLogConfig.excludeRegex == null ||
                    !request.requestURI.matches(requestLogConfig.excludeRegex))
        ) {
          // Log all requests and responses for some users. For other users, be prepared to log
          // requests if we end up returning an error response, but don't log responses.
          val logRequestAndResponse =
              requestLogConfig.emailRegex != null &&
                  user.email.lowercase().matches(requestLogConfig.emailRegex)
          val wrappedRequest = ContentCachingRequestWrapper(request, maxPayloadSize)
          val wrappedResponse =
              if (logRequestAndResponse) {
                ContentCachingResponseWrapper(response)
              } else {
                null
              }

          try {
            chain.doFilter(wrappedRequest, wrappedResponse ?: response)
          } finally {
            try {
              // If we're returning an error response, log the request body for troubleshooting
              // whether or not we're logging all of this user's requests and responses.
              if (logRequestAndResponse || response.status >= 400) {
                mdcPut(
                    "request",
                    payload(wrappedRequest.contentType, wrappedRequest.contentAsByteArray),
                )
                mdcPut("method", wrappedRequest.method)
                mdcPut("queryString", wrappedRequest.queryString)
                mdcPut("uri", wrappedRequest.requestURI)

                wrappedResponse?.let {
                  mdcPut("response", payload(it.contentType, it.contentAsByteArray))
                }

                log.debug("Request ${request.method} ${request.requestURI} ${user.email}")
              }
            } finally {
              wrappedResponse?.copyBodyToResponse()
            }
          }
        } else {
          chain.doFilter(request, response)
        }
      } else {
        chain.doFilter(request, response)
      }
    } finally {
      oldMdc?.let { MDC.setContextMap(it) } ?: MDC.clear()
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
          parsedContentType.charset() ?: StandardCharsets.UTF_8,
      )
    } else {
      null
    }
  }
}
