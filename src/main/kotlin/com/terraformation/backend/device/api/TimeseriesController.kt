package com.terraformation.backend.device.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse413
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.ErrorDetails
import com.terraformation.backend.api.ResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessOrError
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.TimeseriesType
import com.terraformation.backend.db.default_schema.tables.pojos.TimeseriesRow
import com.terraformation.backend.device.db.TimeseriesStore
import com.terraformation.backend.device.model.TimeseriesModel
import com.terraformation.backend.device.model.TimeseriesValueModel
import com.terraformation.backend.log.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Size
import javax.ws.rs.BadRequestException
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RequestMapping("/api/v1/timeseries")
@RestController
class TimeseriesController(
    private val facilityStore: FacilityStore,
    private val parentStore: ParentStore,
    private val timeSeriesStore: TimeseriesStore
) {
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

  @GetMapping
  @Operation(summary = "Lists the timeseries for one or more devices.")
  fun listTimeseries(
      @RequestParam("deviceId") deviceIds: List<DeviceId>
  ): ListTimeseriesResponsePayload {
    val timeseries =
        deviceIds.flatMap { timeSeriesStore.fetchByDeviceId(it) }.map { TimeseriesPayload(it) }

    return ListTimeseriesResponsePayload(timeseries)
  }

  @ApiResponse200(
      "Successfully processed the request. Note that this status will be returned even if the " +
          "server was unable to record some of the values. In that case, the failed values will " +
          "be returned in the response payload.")
  @ApiResponse(
      responseCode = "202",
      description =
          "The request was valid, but the user is still configuring or placing sensors, so the " +
              "timeseries values have not been recorded.")
  @ApiResponse413(
      "The request had more than ${RecordTimeseriesValuesRequestPayload.MAX_VALUES} values.")
  @Operation(summary = "Records new values for one or more timeseries.")
  @PostMapping("/values")
  fun recordTimeseriesValues(
      @RequestBody payload: RecordTimeseriesValuesRequestPayload
  ): ResponseEntity<RecordTimeseriesValuesResponsePayload> {
    val errors = mutableListOf<TimeseriesValuesErrorPayload>()
    val maxValues = RecordTimeseriesValuesRequestPayload.MAX_VALUES

    if (payload.timeseries.size > maxValues) {
      throw WebApplicationException(
          "Request must contain $maxValues or fewer values",
          Response.Status.REQUEST_ENTITY_TOO_LARGE)
    }

    val facilityNotConfigured =
        payload.timeseries
            .asSequence()
            .map { it.deviceId }
            .distinct()
            .any {
              parentStore.getFacilityConnectionState(it) != FacilityConnectionState.Configured
            }
    if (facilityNotConfigured) {
      return ResponseEntity.accepted().body(RecordTimeseriesValuesResponsePayload())
    }

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

    facilityStore.updateLastTimeseriesTimes(payload.timeseries.map { it.deviceId })

    return if (errors.isEmpty()) {
      ResponseEntity.ok(RecordTimeseriesValuesResponsePayload())
    } else {
      ResponseEntity.ok(RecordTimeseriesValuesResponsePayload(errors))
    }
  }

  @Operation(summary = "Returns historical values of timeseries.")
  @PostMapping("/history")
  fun getTimeseriesHistory(
      @RequestBody @Valid payload: GetTimeseriesHistoryRequestPayload
  ): GetTimeseriesHistoryResponsePayload {
    if (payload.seconds != null) {
      if (payload.startTime != null || payload.endTime != null) {
        throw BadRequestException("Must specify seconds or start/end times, but not both.")
      }
      if (payload.seconds < 1) {
        throw BadRequestException("seconds must be greater than 0.")
      }
    } else if (payload.startTime != null && payload.endTime != null) {
      if (payload.endTime <= payload.startTime) {
        throw BadRequestException("endTime must be later than startTime.")
      }
    }

    val timeseriesModels: Map<TimeseriesId, TimeseriesModel> =
        payload.timeseries
            .mapNotNull { timeSeriesStore.fetchOneByName(it.deviceId, it.timeseriesName) }
            .associateBy { it.id }

    val allValues: Map<TimeseriesId, List<TimeseriesValueModel>> =
        when {
          payload.seconds != null -> {
            timeSeriesStore.fetchHistory(payload.seconds, payload.count, timeseriesModels.keys)
          }
          payload.startTime != null && payload.endTime != null -> {
            timeSeriesStore.fetchHistory(
                payload.startTime, payload.endTime, payload.count, timeseriesModels.keys)
          }
          else -> {
            throw BadRequestException("Must specify seconds or startTime/endTime.")
          }
        }

    val valuesPayloads: List<TimeseriesValuesPayload> =
        timeseriesModels.map { (timeseriesId, model) ->
          val valuePayloads =
              allValues[timeseriesId]?.map { TimeseriesValuePayload(it) } ?: emptyList()
          TimeseriesValuesPayload(model.deviceId, model.name, valuePayloads)
        }

    return GetTimeseriesHistoryResponsePayload(valuesPayloads)
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
    @ArraySchema(
        arraySchema =
            Schema(description = "Values that the server was not able to successfully record."))
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
) {
  constructor(model: TimeseriesValueModel) : this(model.createdTime, model.value)
}

data class CreateTimeseriesRequestPayload(val timeseries: List<CreateTimeseriesEntry>)

data class RecordTimeseriesValuesRequestPayload(
    @Size(
        max = MAX_VALUES,
    )
    val timeseries: List<TimeseriesValuesPayload>
) {
  companion object {
    const val MAX_VALUES = 1000
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Results of a request to record timeseries values.")
data class RecordTimeseriesValuesResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of values that the server failed to record. Will not be included if " +
                        "all the values were recorded successfully."))
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

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TimeseriesPayload(
    @Schema(
        description = "ID of device that produces this timeseries.",
    )
    val deviceId: DeviceId,
    val timeseriesName: String,
    val type: TimeseriesType,
    @Schema(
        description =
            "Number of significant fractional digits (after the decimal point), if this is a " +
                "timeseries with non-integer numeric values.")
    val decimalPlaces: Int?,
    @Schema(description = "Units of measure for values in this timeseries.", example = "volts")
    val units: String?,
    @Schema(description = "If any values have been recorded for the timeseries, the latest one.")
    val latestValue: TimeseriesValuePayload?,
) {
  constructor(
      model: TimeseriesModel
  ) : this(
      model.deviceId,
      model.name,
      model.type,
      model.decimalPlaces,
      model.units,
      model.latestValue?.let { TimeseriesValuePayload(it.createdTime, it.value) })
}

data class ListTimeseriesResponsePayload(val timeseries: List<TimeseriesPayload>) :
    SuccessResponsePayload

data class TimeseriesIdPayload(val deviceId: DeviceId, val timeseriesName: String)

data class GetTimeseriesHistoryRequestPayload(
    @Schema(
        description =
            "Start of time range to query. If this is non-null, endTime must also be specified, " +
                "and seconds must be null or absent.")
    val startTime: Instant?,
    @Schema(
        description =
            "End of time range to query. If this is non-null, startTime must also be specified, " +
                "and seconds must be null or absent.")
    val endTime: Instant?,
    @Schema(
        description =
            "Number of seconds in the past to start the time range. If this is non-null, " +
                "startTime and endTime must be null or absent.")
    val seconds: Long?,
    @field:Min(1)
    @field:Max(50)
    @Schema(
        description =
            "Number of values to return. The time range is divided into this many equal " +
                "intervals, and a value is returned from each interval if available.")
    val count: Int,
    @ArraySchema(
        arraySchema = Schema(description = "Timeseries to query. May be from different devices."))
    @field:Size(max = 100)
    val timeseries: List<TimeseriesIdPayload>,
)

data class GetTimeseriesHistoryResponsePayload(val values: List<TimeseriesValuesPayload>)
