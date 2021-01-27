package com.terraformation.seedbank.api.rhizo

import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.db.DeviceFetcher
import com.terraformation.seedbank.db.TimeSeriesFetcher
import com.terraformation.seedbank.db.TimeSeriesWriter
import com.terraformation.seedbank.db.TimeseriesType
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.tags.Tag
import javax.servlet.http.HttpServletRequest
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Emulates the parts of rhizo-server's resources API required to serve requests from a device
 * manager instance.
 */
@Controller
@Hidden
@Tag(name = "DeviceManager")
class ResourceController(
    private val deviceFetcher: DeviceFetcher,
    private val timeSeriesFetcher: TimeSeriesFetcher,
    private val timeSeriesWriter: TimeSeriesWriter
) {
  private val log = perClassLogger()

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
    val dataType = TimeseriesType.forId(type) ?: throw UnsupportedDataTypeException()

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

  // Ideally we'd use "/api/v1/resources/{*path}" but that breaks Swagger doc generation; see
  // https://github.com/springdoc/springdoc-openapi/issues/1034
  @GetMapping("/api/v1/resources/**", produces = [MediaType.TEXT_PLAIN_VALUE])
  @ResponseBody
  fun getResource(request: HttpServletRequest): String {
    val path = request.requestURI.substringAfter("/api/v1/resources")
    val sequenceName = path.substringAfterLast('/')
    val deviceTopic = path.substringBeforeLast('/').substring(1)
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
