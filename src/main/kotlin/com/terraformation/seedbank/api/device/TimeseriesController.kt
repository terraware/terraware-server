package com.terraformation.seedbank.api.device

import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SimpleSuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.DeviceManagerAppEndpoint
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.DeviceStore
import com.terraformation.seedbank.db.TimeSeriesStore
import com.terraformation.seedbank.db.TimeseriesType
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.math.BigDecimal
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RequestMapping("/api/v1/seedbank/timeseries")
@RestController
class TimeseriesController(
    private val config: TerrawareServerConfig,
    private val deviceStore: DeviceStore,
    private val timeSeriesStore: TimeSeriesStore,
) {
  private val log = perClassLogger()

  @ApiResponse(responseCode = "200", description = "Timeseries value recorded successfully.")
  @ApiResponse404
  @Operation(summary = "Report a numeric timeseries value.")
  @PostMapping(consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
  fun reportNumericValue(
      @RequestParam
      @Schema(
          description =
              "Name of device. Must match the device name as defined in the per-site configuration.")
      device: String,
      @RequestParam
      @Schema(
          description =
              "Name of timeseries. If the timeseries does not already exist, it will be created.")
      timeseries: String,
      @RequestParam value: BigDecimal,
      @RequestParam(required = false)
      @Schema(
          description =
              "The type of unit the value measures. Ignored if the timeseries already exists.",
          example = "MB")
      units: String?,
      @RequestParam(required = false)
      @Schema(
          description =
              "Number of significant decimal places. Ignored if the timeseries already exists. " +
                  "Values are always stored in their entirety but dashboards and reports can use " +
                  "this value to control how many decimal places to display.")
      decimalPlaces: Int?,
      @RequestParam(required = false)
      @Schema(description = "Alias for facilityId for backward compatibility.")
      siteModuleId: Long?,
      @RequestParam(required = false)
      @Schema(
          description =
              "Which facility the device is in, if it isn't in the seed bank's default facility. " +
                  "Must match the facility ID the device is associated with in the per-site " +
                  "configuration.")
      facilityId: Long?,
  ): SimpleSuccessResponsePayload {
    val effectiveFacilityId = facilityId ?: siteModuleId
    val deviceId =
        deviceStore.getDeviceIdByName(effectiveFacilityId ?: config.facilityId, device)
            ?: throw NotFoundException("Device $device does not exist")
    val timeseriesId =
        timeSeriesStore.getTimeseriesIdByName(deviceId, timeseries)
            ?: timeSeriesStore.create(
                deviceId, timeseries, TimeseriesType.Numeric, units, decimalPlaces)

    timeSeriesStore.insertValue(timeseriesId, value.toPlainString())

    log.info("Got timeseries value $device/$timeseries = $value")
    return SimpleSuccessResponsePayload()
  }
}
