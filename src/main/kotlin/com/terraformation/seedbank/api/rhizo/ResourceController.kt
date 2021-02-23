package com.terraformation.seedbank.api.rhizo

import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.db.DeviceFetcher
import com.terraformation.seedbank.db.TimeSeriesFetcher
import com.terraformation.seedbank.db.TimeSeriesWriter
import com.terraformation.seedbank.db.TimeseriesType
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Emulates the parts of rhizo-server's resources API required to serve requests from a device
 * manager instance.
 */
@Controller
@Tag(name = "DeviceManager")
class ResourceController(
    private val deviceFetcher: DeviceFetcher,
    private val timeSeriesFetcher: TimeSeriesFetcher,
    private val timeSeriesWriter: TimeSeriesWriter
) {
  private val log = perClassLogger()

  @ApiResponse(
      responseCode = "200",
      description =
          "The timeseries was successfully created, or it already existed so no action was needed.")
  @ApiResponse404(description = "No device with the given path was defined.")
  @Operation(summary = "Create a new resource (timeseries) for device metrics.")
  @PostMapping("/api/v1/resources", produces = [MediaType.TEXT_PLAIN_VALUE])
  @ResponseBody
  fun createResource(
      @AuthenticationPrincipal identity: ClientIdentity,
      @RequestParam path: String,
      @RequestParam name: String,
      @RequestParam type: Int,
      @RequestParam data_type: Int,
      @RequestParam min_storage_interval: Int?,
      @RequestParam decimal_places: Int?,
      @RequestParam units: String?,
      @RequestParam max_history: Int?
  ): String {
    val dataType = TimeseriesType.forId(data_type) ?: throw UnsupportedDataTypeException()

    if (type != ResourceType.SEQUENCE) {
      log.error("Unable to create sequence of type $type")
      throw UnsupportedResourceTypeException()
    }

    val deviceId = deviceFetcher.getDeviceIdForMqttTopic(path.substring(1))
    if (deviceId == null) {
      log.warn("Unable to create sequence $name for unknown device $path")
      throw NotFoundException()
    }

    return try {
      timeSeriesWriter.create(deviceId, name, dataType, units, decimal_places)
      log.info("Created timeseries $name for device $path")
      "Timeseries created"
    } catch (e: DuplicateKeyException) {
      log.info("Ignored create request for existing timeseries $name for device $path")
      "Timeseries already exists"
    }
  }

  @ApiResponse(
      responseCode = "200",
      description = "The resource exists. The response body is just the string \"Found\".")
  @ApiResponse404
  @GetMapping("/api/v1/resources/{*path}", produces = [MediaType.TEXT_PLAIN_VALUE])
  @Operation(summary = "Tests for the existence of a resource (timeseries).")
  @ResponseBody
  fun getResource(
      @PathVariable
      @Schema(
          description =
              "Path of resource, typically including organization, site, site module, device, " +
                  "and timeseries name.",
          example = "terraformation/pac-flight/ohana/BMU-L/system_voltage")
      path: String
  ): String {
    // Split the path into an MQTT topic and sequence name, /X/Y/Z -> (X/Y, Z)
    val deviceTopic = path.substringBeforeLast('/').substring(1)
    val sequenceName = path.substringAfterLast('/')
    if (timeSeriesFetcher.getIdByMqttTopic(deviceTopic, sequenceName) != null) {
      return "Found"
    } else {
      throw NotFoundException()
    }
  }
}

object ResourceType {
  const val SEQUENCE = 21
}

@Suppress("unused")
enum class ResourceDataType(val code: Int) {
  NUMERIC(1),
  TEXT(2),
  IMAGE(3);

  companion object {
    fun forCode(code: Int) = values().firstOrNull { it.code == code }
  }
}

@ResponseStatus(HttpStatus.BAD_REQUEST, reason = "Data type not supported")
class UnsupportedDataTypeException : RuntimeException()

@ResponseStatus(HttpStatus.BAD_REQUEST, reason = "Resource type not supported")
class UnsupportedResourceTypeException : RuntimeException()
