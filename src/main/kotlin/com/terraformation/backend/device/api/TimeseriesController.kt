package com.terraformation.backend.device.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.ErrorDetails
import com.terraformation.backend.api.ResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessOrError
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.TimeseriesType
import com.terraformation.backend.db.tables.pojos.TimeseriesRow
import com.terraformation.backend.device.db.TimeseriesStore
import com.terraformation.backend.log.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RequestMapping(
    "/api/v1/timeseries",
    // TODO: Remove the following once the device manager is updated to use /api/v1/timeseries
    "/api/v1/seedbank/timeseries")
@RestController
class TimeseriesController(private val timeSeriesStore: TimeseriesStore) {
  private val log = perClassLogger()

  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Defines a list of timeseries for one or more devices.",
      description =
          "If there are existing timeseries with the same names, the old definitions will be" +
              " overwritten.")
  @PostMapping("/create")
  fun createMultipleTimeseries(
      @RequestBody payload: CreateTimeseriesRequestPayload
  ): SimpleSuccessResponsePayload {
    timeSeriesStore.createOrUpdate(payload.timeseries.map { it.toRow() })
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "Successfully processed the request. Note that this status will be returned even if " +
              "the server was unable to record some of the values. In that case, the failed " +
              "values will be returned in the response payload.")
  @Operation(summary = "Records new values for one or more timeseries.")
  @PostMapping("/values")
  fun recordTimeseriesValues(
      @RequestBody payload: RecordTimeseriesValuesRequestPayload
  ): RecordTimeseriesValuesResponsePayload {
    val errors = mutableListOf<TimeseriesValuesErrorPayload>()

    payload.timeseries.forEach { valuesEntry ->
      val timeseriesName = valuesEntry.timeseriesName
      val deviceId = valuesEntry.deviceId
      val timeseriesRow = timeSeriesStore.fetchOneByName(deviceId, timeseriesName)
      val timeseriesId = timeseriesRow?.id

      if (timeseriesId == null) {
        log.error("Timeseries $timeseriesName for device $deviceId not found or no permission")
        errors.add(
            TimeseriesValuesErrorPayload(
                deviceId, timeseriesName, valuesEntry.values, "Timeseries not found"))
      } else {
        // Group failures by error message.
        val failures = mutableMapOf<String, MutableList<TimeseriesValuePayload>>()

        valuesEntry.values.forEach { valueEntry ->
          val timestamp = valueEntry.timestamp
          try {
            timeSeriesStore.insertValue(deviceId, timeseriesId, valueEntry.value, timestamp)
          } catch (e: Exception) {
            val message =
                when (e) {
                  is DuplicateKeyException -> {
                    log.info("Duplicate value for timeseries $timeseriesId timestamp $timestamp")
                    "Already have a value with this timestamp"
                  }
                  else -> {
                    log.error(
                        "Failed to insert value ${valueEntry.value} for timeseries $timeseriesId",
                        e)
                    "Unexpected error while saving value"
                  }
                }

            failures.computeIfAbsent(message) { mutableListOf() }.add(valueEntry)
          }
        }

        failures.forEach { (message, values) ->
          errors.add(TimeseriesValuesErrorPayload(deviceId, timeseriesName, values, message))
        }
      }
    }

    return if (errors.isEmpty()) {
      RecordTimeseriesValuesResponsePayload()
    } else {
      RecordTimeseriesValuesResponsePayload(errors)
    }
  }
}

data class CreateTimeseriesEntry(
    @Schema(
        description = "ID of device that produces this timeseries.",
    )
    val deviceId: DeviceId,
    @Schema(
        description =
            "Name of this timeseries. Duplicate timeseries names for the same device aren't " +
                "allowed, but different devices can have timeseries with the same name.")
    val timeseriesName: String,
    val type: TimeseriesType,
    @Schema(
        description =
            "Number of significant fractional digits (after the decimal point), if this is a " +
                "timeseries with non-integer numeric values.")
    val decimalPlaces: Int?,
    @Schema(description = "Units of measure for values in this timeseries.", example = "volts")
    val units: String?,
) {
  fun toRow() =
      TimeseriesRow(
          decimalPlaces = decimalPlaces,
          deviceId = deviceId,
          name = timeseriesName,
          typeId = type,
          units = units,
      )
}

data class TimeseriesValuesErrorPayload(
    @Schema(
        description = "Device ID as specified in the failing request.",
    )
    val deviceId: DeviceId,
    @Schema(description = "Name of timeseries as specified in the failing request.")
    val timeseriesName: String,
    @Schema(description = "Values that the server was not able to successfully record.")
    val values: List<TimeseriesValuePayload>,
    @Schema(
        description = "Human-readable details about the failure.",
    )
    val message: String,
)

data class TimeseriesValuesPayload(
    @Schema(
        description = "ID of device that produced this value.",
    )
    val deviceId: DeviceId,
    @Schema(
        description =
            "Name of timeseries. This must be the name of a timeseries that has already been " +
                "created for the device.")
    val timeseriesName: String,
    val values: List<TimeseriesValuePayload>
)

data class TimeseriesValuePayload(
    val timestamp: Instant,
    @Schema(
        description =
            "Value to record. If the timeseries is of type Numeric, this must be a decimal " +
                "or integer value in string form. If the timeseries is of type Text, this can be " +
                "an arbitrary string.")
    val value: String
)

data class CreateTimeseriesRequestPayload(val timeseries: List<CreateTimeseriesEntry>)

data class RecordTimeseriesValuesRequestPayload(val timeseries: List<TimeseriesValuesPayload>)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Results of a request to record timeseries values.")
data class RecordTimeseriesValuesResponsePayload(
    @Schema(
        description =
            "List of values that the server failed to record. Will not be included if all the " +
                "values were recorded successfully.")
    val failures: List<TimeseriesValuesErrorPayload>?,
    override val status: SuccessOrError,
    val error: ErrorDetails?
) : ResponsePayload {
  constructor() : this(null, SuccessOrError.Ok, null)
  constructor(
      failures: List<TimeseriesValuesErrorPayload>
  ) : this(
      failures,
      SuccessOrError.Error,
      ErrorDetails("One or more timeseries values could not be recorded."))
}
