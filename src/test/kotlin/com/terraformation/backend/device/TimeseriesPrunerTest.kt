package com.terraformation.backend.device

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.tables.records.TimeseriesValuesRecord
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES_VALUES
import com.terraformation.backend.mockUser
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TimeseriesPrunerTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val pruner by lazy { TimeseriesPruner(clock, dslContext) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertFacility()
    insertDevice()

    clock.instant = daysSinceEpoch(1000)
  }

  @Test
  fun `does not delete from timeseries with null retention days`() {
    insertTimeseries(retentionDays = null)
    insertTimeseriesValue()

    val initialValues = dslContext.fetch(TIMESERIES_VALUES)

    pruner.pruneTimeseriesValues()

    assertTableEquals(initialValues)
  }

  @Test
  fun `honors different retention days on different timeseries`() {
    val timeseriesId1 = insertTimeseries(retentionDays = 10)
    insertTimeseriesValue(createdTime = daysAgo(30), value = "30 days old")
    insertTimeseriesValue(createdTime = daysAgo(11), value = "11 days old")
    insertTimeseriesValue(createdTime = daysAgo(10), value = "10 days old")
    val timeseriesId2 = insertTimeseries(retentionDays = 30)
    insertTimeseriesValue(createdTime = daysAgo(31), value = "31 days old")
    insertTimeseriesValue(createdTime = daysAgo(30), value = "30 days old")
    insertTimeseriesValue(createdTime = daysAgo(10), value = "10 days old")

    pruner.pruneTimeseriesValues()

    assertTableEquals(
        listOf(
            TimeseriesValuesRecord(timeseriesId1, daysAgo(10), "10 days old"),
            TimeseriesValuesRecord(timeseriesId2, daysAgo(30), "30 days old"),
            TimeseriesValuesRecord(timeseriesId2, daysAgo(10), "10 days old"),
        )
    )
  }

  @Test
  fun `only deletes up to a certain number of rows from a timeseries`() {
    val timeseriesId = insertTimeseries(retentionDays = 5)
    List(10) { index -> insertTimeseriesValue(createdTime = daysAgo(index)) }

    pruner.maxRowsToDelete = 3
    pruner.pruneTimeseriesValues()

    assertTableEquals(List(7) { TimeseriesValuesRecord(timeseriesId, daysAgo(it), "1") })
  }

  private fun daysSinceEpoch(days: Int) = Instant.EPOCH.plus(days.toLong(), ChronoUnit.DAYS)

  private fun daysAgo(days: Int) = clock.instant().minus(days.toLong(), ChronoUnit.DAYS)
}
