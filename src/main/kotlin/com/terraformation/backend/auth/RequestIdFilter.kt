package com.terraformation.backend.auth

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import java.nio.ByteBuffer
import java.util.UUID
import org.apache.commons.codec.binary.Base32
import org.slf4j.MDC

/**
 * Adds a unique identifier for each incoming request to the logging context and the request
 * attributes. This is included in log messages and can be retrieved by any other code that needs to
 * know which request triggered it.
 *
 * This should come early in the filter chain.
 */
class RequestIdFilter : Filter {
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
    val oldMdc = MDC.getCopyOfContextMap()
    val requestId = "${requestIdPrefix}_${request.requestId}"

    try {
      MDC.put("requestId", requestId)
      request.setAttribute("terrawareRequestId", requestId)

      chain.doFilter(request, response)
    } finally {
      oldMdc?.let { MDC.setContextMap(it) } ?: MDC.clear()
    }
  }
}
