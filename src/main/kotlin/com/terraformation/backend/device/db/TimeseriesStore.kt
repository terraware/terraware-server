package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.TimeseriesType
import com.terraformation.backend.db.default_schema.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES_VALUES
import com.terraformation.backend.device.model.TimeseriesModel
import com.terraformation.backend.device.model.TimeseriesValueModel
import jakarta.inject.Named
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToLong
import org.jooq.DSLContext
import org.jooq.Record3
import org.jooq.Select
import org.jooq.impl.DSL

@Named
class TimeseriesStore(private val clock: Clock, private val dslContext: DSLContext) {
  /** Subquery to retrieve the latest value when querying the TIMESERIES table. */
  private val latestValueMultiset =
      DSL.multiset(
              DSL.selectFrom(TIMESERIES_VALUES)
                  .where(TIMESERIES_VALUES.TIMESERIES_ID.eq(TIMESERIES.ID))
                  .orderBy(TIMESERIES_VALUES.CREATED_TIME.desc())
                  .limit(1))
          .convertFrom { result -> result.firstOrNull()?.let { TimeseriesValueModel.ofRecord(it) } }

  fun fetchOneByName(deviceId: DeviceId, name: String): TimeseriesModel? {
    if (!currentUser().canReadTimeseries(deviceId)) {
      return null
    }

    return dslContext
        .select(TIMESERIES.asterisk(), latestValueMultiset)
        .from(TIMESERIES)
        .where(TIMESERIES.DEVICE_ID.eq(deviceId))
        .and(TIMESERIES.NAME.eq(name))
        .fetchOne { TimeseriesModel(it, it[latestValueMultiset]) }
  }

  fun fetchByDeviceId(deviceId: DeviceId): List<TimeseriesModel> {
    requirePermissions { readTimeseries(deviceId) }

    return dslContext
        .select(TIMESERIES.asterisk(), latestValueMultiset)
        .from(TIMESERIES)
        .where(TIMESERIES.DEVICE_ID.eq(deviceId))
        .orderBy(TIMESERIES.NAME)
        .fetch { TimeseriesModel(it, it[latestValueMultiset]) }
  }

  /**
   * Creates a new timeseries or updates an existing one.
   *
   * @throws org.jooq.exception.DataAccessException
   */
  private fun createOrUpdate(row: TimeseriesRow): TimeseriesId {
    val deviceId = row.deviceId ?: throw IllegalArgumentException("No device ID specified")

    requirePermissions { createTimeseries(deviceId) }

    val userId = currentUser().userId

    return with(TIMESERIES) {
      dslContext
          .insertInto(TIMESERIES)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, clock.instant())
          .set(DECIMAL_PLACES, row.decimalPlaces)
          .set(DEVICE_ID, row.deviceId)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NAME, row.name)
          .set(TYPE_ID, row.typeId)
          .set(UNITS, row.units)
          .onConflict(DEVICE_ID, NAME)
          .doUpdate()
          .set(DECIMAL_PLACES, row.decimalPlaces)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(TYPE_ID, row.typeId)
          .set(UNITS, row.units)
          .returning(ID)
          .fetchOne()
          ?.id!!
    }
  }

  /**
   * Creates or updates a list of timeseries. This is all-or-nothing: if any of the changes fail,
   * none of them are applied.
   *
   * @return List of timeseries IDs in the same order as the entries in [rows].
   */
  fun createOrUpdate(rows: List<TimeseriesRow>): List<TimeseriesId> {
    return dslContext.transactionResult { _ -> rows.map { createOrUpdate(it) } }
  }

  fun insertValue(
      deviceId: DeviceId,
      timeseriesId: TimeseriesId,
      value: String,
      createdTime: Instant
  ) {
    requirePermissions { updateTimeseries(deviceId) }

    val (type, decimalPlaces) =
        dslContext
            .select(TIMESERIES.TYPE_ID, TIMESERIES.DECIMAL_PLACES)
            .from(TIMESERIES)
            .where(TIMESERIES.ID.eq(timeseriesId))
            .and(TIMESERIES.DEVICE_ID.eq(deviceId))
            .fetchOne()
            ?: throw TimeseriesNotFoundException(deviceId)

    val roundedValue =
        if (type == TimeseriesType.Numeric && decimalPlaces != null) {
          BigDecimal(value)
              .setScale(decimalPlaces, RoundingMode.HALF_UP)
              .stripTrailingZeros()
              .toPlainString()
        } else {
          value
        }

    with(TIMESERIES_VALUES) {
      dslContext
          .insertInto(TIMESERIES_VALUES)
          .set(TIMESERIES_ID, timeseriesId)
          .set(CREATED_TIME, createdTime)
          .set(VALUE, roundedValue)
          .execute()
    }
  }

  /**
   * Returns sample timeseries values from a time range in the past. The time range is divided into
   * equal-sized slices, and a value is returned from each slice for each of the requested
   * timeseries.
   */
  fun fetchHistory(
      startTime: Instant,
      endTime: Instant,
      count: Int,
      timeseriesIds: Collection<TimeseriesId>
  ): Map<TimeseriesId, List<TimeseriesValueModel>> {
    val interval = Duration.between(startTime, endTime).dividedBy(count.toLong())

    /*
     * Based on experimentation, there doesn't appear to be a good way to get PostgreSQL to generate
     * an efficient execution plan for "get the latest value from each time slice for a list of
     * timeseries ID" queries; it always ends up scanning much more data than needed. So instead,
     * we query each slice for each timeseries separately. But we don't want thousands of round
     * trips to the database, so we combine them using the `UNION ALL` operator. That could end up
     * being a gargantuan query if the list of timeseries IDs is large or the count is high, so we
     * split it up into chunks and combine the results.
     *
     * In tests on PostgreSQL 13, this approach gives a more than 10x speedup compared to more
     * compact approaches such as using an `IN` clause to query all the timeseries IDs for a slice
     * at the same time or using `PARTITION` to calculate the slice boundaries as part of the query.
     * It may be worth repeating this experiment as we move to newer database versions over time.
     */
    val timeSlices =
        sequence<Pair<Instant, Instant>> {
          var sliceStart = startTime

          while (sliceStart < endTime) {
            val sliceEnd = sliceStart + interval
            yield(sliceStart to sliceEnd)
            sliceStart = sliceEnd
          }
        }

    val sliceQueries =
        timeSlices.flatMap { (sliceStart, sliceEnd) ->
          timeseriesIds.map { id ->
            with(TIMESERIES_VALUES) {
              @Suppress("USELESS_CAST") // Needs to be a supertype of unionAll()'s type
              dslContext
                  .select(TIMESERIES_ID, CREATED_TIME, VALUE)
                  .from(TIMESERIES_VALUES)
                  .where(CREATED_TIME.ge(sliceStart))
                  .and(CREATED_TIME.lt(sliceEnd))
                  .and(TIMESERIES_ID.eq(id))
                  .orderBy(CREATED_TIME.desc())
                  .limit(1) as Select<Record3<TimeseriesId?, Instant?, String?>>
            }
          }
        }

    return sliceQueries
        .chunked(MAX_SLICES_PER_HISTORY_QUERY)
        .map { chunk ->
          chunk.reduce { combinedQuery, sliceQuery -> combinedQuery.unionAll(sliceQuery) }
        }
        .flatMap { combinedQuery -> combinedQuery.fetch() }
        .mapNotNull { TimeseriesValueModel.ofRecord(it) }
        .groupBy { it.timeseriesId }
  }

  /**
   * Returns sample timeseries values from a time range starting at some number of seconds in the
   * past and extending to the current time. The time range is divided into equal-sized slices, and
   * a value is returned from each slice for each of the requested timeseries.
   */
  fun fetchHistory(
      seconds: Long,
      count: Int,
      timeseriesIds: Collection<TimeseriesId>
  ): Map<TimeseriesId, List<TimeseriesValueModel>> {
    /*
     * We want this method to return consistent results for a given timeseries if it's called
     * repeatedly with the same [seconds] and [count] values. Since we're going to be dividing the
     * time range up into slices and returning the newest value from each slice, that means the
     * slice boundaries need to be consistent across calls, which means we can't just naively
     * subtract [seconds] from the current time and divide the resulting range by [count].
     *
     * Instead, we calculate the amount of time per slice and round the current time up to the
     * next interval to get the end time, then subtract the number of seconds from that to get the
     * start time.
     */
    val interval = seconds.toFloat() * 1000.0 / count.toFloat()
    val endTime =
        Instant.ofEpochMilli(
            (ceil(clock.instant().toEpochMilli() / interval) * interval).roundToLong())
    val startTime = endTime.minusSeconds(seconds)

    return fetchHistory(startTime, endTime, count, timeseriesIds)
  }

  /**
   * Returns the timestamps from the given list that already have values for the given timeseries.
   * This is used to detect duplicate insertion attempts.
   */
  fun checkExistingValues(
      timeseriesId: TimeseriesId,
      timestamps: Collection<Instant>
  ): Set<Instant> {
    return with(TIMESERIES_VALUES) {
      dslContext
          .select(CREATED_TIME)
          .from(TIMESERIES_VALUES)
          .where(TIMESERIES_ID.eq(timeseriesId))
          .and(CREATED_TIME.`in`(timestamps))
          .fetchSet(CREATED_TIME.asNonNullable())
    }
  }

  companion object {
    /**
     * When pulling history data from the database, limit each SQL query to this many individual
     * time slices. Each slice turns into a separate `SELECT` statement, joined together with `UNION
     * ALL`.
     */
    const val MAX_SLICES_PER_HISTORY_QUERY = 500
  }
}
