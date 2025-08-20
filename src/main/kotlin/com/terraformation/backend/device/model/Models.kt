package com.terraformation.backend.device.model

import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.TimeseriesType
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES_VALUES
import java.time.Instant
import org.jooq.Record

data class TimeseriesValueModel(
    val timeseriesId: TimeseriesId,
    val createdTime: Instant,
    val value: String,
) {
  companion object {
    fun ofRecord(record: Record): TimeseriesValueModel? {
      return TimeseriesValueModel(
          record[TIMESERIES_VALUES.TIMESERIES_ID] ?: return null,
          record[TIMESERIES_VALUES.CREATED_TIME] ?: return null,
          record[TIMESERIES_VALUES.VALUE] ?: return null,
      )
    }
  }
}

data class TimeseriesModel(
    val id: TimeseriesId,
    val deviceId: DeviceId,
    val name: String,
    val type: TimeseriesType,
    val decimalPlaces: Int?,
    val units: String?,
    val latestValue: TimeseriesValueModel? = null,
) {
  constructor(
      record: Record,
      latestValue: TimeseriesValueModel?,
  ) : this(
      record[TIMESERIES.ID] ?: throw IllegalArgumentException("ID must be non-null"),
      record[TIMESERIES.DEVICE_ID] ?: throw IllegalArgumentException("Device ID must be non-null"),
      record[TIMESERIES.NAME] ?: throw IllegalArgumentException("Name must be non-null"),
      record[TIMESERIES.TYPE_ID] ?: throw IllegalArgumentException("Type must be non-null"),
      record[TIMESERIES.DECIMAL_PLACES],
      record[TIMESERIES.UNITS],
      latestValue,
  )
}
