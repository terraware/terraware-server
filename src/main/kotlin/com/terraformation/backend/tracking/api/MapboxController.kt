package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.tracking.mapbox.MapboxRequestFailedException
import com.terraformation.backend.tracking.mapbox.MapboxService
import io.swagger.v3.oas.annotations.responses.ApiResponse
import javax.ws.rs.NotFoundException
import javax.ws.rs.ServiceUnavailableException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/mapbox")
@RestController
@TrackingEndpoint
class MapboxController(
    private val config: TerrawareServerConfig,
    private val mapboxService: MapboxService,
) {
  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The server is not configured to return Mapbox tokens.")
  @ApiResponse(
      responseCode = "503",
      description = "The server is temporarily unable to generate a new Mapbox token.")
  @GetMapping("/token")
  fun getMapboxToken(): GetMapboxTokenResponsePayload {
    val token =
        if (mapboxService.enabled) {
          try {
            mapboxService.generateTemporaryToken()
          } catch (e: MapboxRequestFailedException) {
            throw ServiceUnavailableException("Temporarily unable to generate Mapbox tokens.")
          }
        } else {
          throw NotFoundException("The server is not configured to return Mapbox tokens.")
        }

    return GetMapboxTokenResponsePayload(token)
  }
}

data class GetMapboxTokenResponsePayload(val token: MapboxService.GeneratedTemporaryToken) :
    SuccessResponsePayload
