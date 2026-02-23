package com.terraformation.backend.gis.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.gis.geoserver.GeoServerClient
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.contentType
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/gis/wfs")
@RestController
class GeoServerProxyController(private val geoServerClient: GeoServerClient) {
  @GetMapping
  @Operation(
      summary = "Forwards a WFS API request to GeoServer.",
      description =
          "Query string parameters are passed to GeoServer, but headers aren't. The response " +
              "from GeoServer, if any, will be returned verbatim. Only available for internal " +
              "users.",
  )
  fun proxyGetRequest(
      request: HttpServletRequest,
  ): ResponseEntity<ByteArray> {
    return runBlocking {
      val response = geoServerClient.proxyGetRequest(request.parameterMap)
      val mediaType =
          response.contentType()?.toString()?.let { MediaType.parseMediaType(it) }
              ?: MediaType.APPLICATION_OCTET_STREAM
      ResponseEntity.status(response.status.value)
          .contentType(mediaType)
          .apply {
            response.headers[HttpHeaders.CONTENT_DISPOSITION]?.let {
              header(HttpHeaders.CONTENT_DISPOSITION, it)
            }
          }
          .body(response.bodyAsBytes())
    }
  }
}
