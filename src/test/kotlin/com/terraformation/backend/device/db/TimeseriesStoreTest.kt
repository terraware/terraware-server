package com.terraformation.backend.device.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.TimeseriesType
import com.terraformation.backend.db.default_schema.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.default_schema.tables.pojos.TimeseriesValuesRow
import com.terraformation.backend.db.default_schema.tables.records.TimeseriesValuesRecord
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES_VALUES
import com.terraformation.backend.device.model.TimeseriesModel
import com.terraformation.backend.device.model.TimeseriesValueModel
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class TimeseriesStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private lateinit var store: TimeseriesStore

  private lateinit var deviceId: DeviceId
  private lateinit var timeseriesRow: TimeseriesRow

  @BeforeEach
  fun setUp() {
    store = TimeseriesStore(clock, dslContext)

    insertOrganization()
    insertFacility()
    deviceId = insertDevice()

    every { user.canCreateTimeseries(any()) } returns true
    every { user.canReadDevice(any()) } returns true
    every { user.canReadTimeseries(any()) } returns true
    every { user.canUpdateTimeseries(any()) } returns true

    timeseriesRow =
        TimeseriesRow(
            createdBy = user.userId,
            createdTime = clock.instant(),
            decimalPlaces = 10,
            deviceId = deviceId,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = "test",
            typeId = TimeseriesType.Numeric,
            units = "units",
        )
  }

  @Test
  fun `fetchOneByName returns timeseries if it exists`() {
    timeseriesDao.insert(timeseriesRow)

    val name = timeseriesRow.name!!
    val expected =
        TimeseriesModel(
            timeseriesRow.id!!,
            deviceId,
            name,
            timeseriesRow.typeId!!,
            timeseriesRow.decimalPlaces,
            timeseriesRow.units,
        )

    val actual = store.fetchOneByName(deviceId, name)
    assertEquals(expected, actual)
  }

  @Test
  fun `fetchOneByName returns null if user has no permission to read timeseries`() {
    timeseriesDao.insert(timeseriesRow)

    every { user.canReadTimeseries(deviceId) } returns false

    assertNull(store.fetchOneByName(deviceId, timeseriesRow.name!!))
  }

  @Test
  fun `createOrUpdate creates new timeseries`() {
    val timeseriesIds = store.createOrUpdate(listOf(timeseriesRow))
    assertEquals(1, timeseriesIds.size, "Number of IDs returned")

    val timeseriesId = timeseriesIds.first()
    val expected = timeseriesRow.copy(id = timeseriesId)
    val actual = timeseriesDao.fetchOneById(timeseriesId)
    assertEquals(expected, actual)
  }

  @Test
  fun `createOrUpdate updates existing timeseries with same name and device ID`() {
    val otherUser = mockUser()
    val otherUserId = insertUser()
    every { otherUser.userId } returns otherUserId
    every { otherUser.canCreateTimeseries(any()) } returns true

    timeseriesDao.insert(timeseriesRow)
    val timeseriesId = timeseriesRow.id!!

    clock.instant = Instant.EPOCH.plusSeconds(60)

    val modified =
        TimeseriesRow(
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            decimalPlaces = 1000,
            deviceId = timeseriesRow.deviceId,
            modifiedBy = otherUser.userId,
            modifiedTime = clock.instant(),
            name = timeseriesRow.name,
            typeId = TimeseriesType.Text,
            units = "newUnits",
        )

    val timeseriesIds = otherUser.run { store.createOrUpdate(listOf(modified)) }

    assertEquals(1, timeseriesIds.size, "Number of IDs returned")
    assertEquals(timeseriesId, timeseriesIds.first(), "ID of modified row")

    val expected = modified.copy(id = timeseriesId)
    val actual = timeseriesDao.fetchOneById(timeseriesId)
    assertEquals(expected, actual)
  }

  @Test
  fun `createOrUpdate throws exception if user has no permission to create timeseries`() {
    every { user.canCreateTimeseries(any()) } returns false

    assertThrows<AccessDeniedException> { store.createOrUpdate(listOf(timeseriesRow)) }
  }

  @Test
  fun `insertValue inserts new value`() {
    timeseriesDao.insert(timeseriesRow)
    val timeseriesId = timeseriesRow.id!!
    val value = "1.5678"

    store.insertValue(deviceId, timeseriesId, value, Instant.EPOCH)

    val expected = listOf(TimeseriesValuesRow(timeseriesId, Instant.EPOCH, value))
    val actual = dslContext.selectFrom(TIMESERIES_VALUES).fetchInto(TimeseriesValuesRow::class.java)
    assertEquals(expected, actual)
  }

  @Test
  fun `insertValue rounds numeric value to correct number of decimal places`() {
    timeseriesRow.decimalPlaces = 3
    timeseriesDao.insert(timeseriesRow)
    val timeseriesId = timeseriesRow.id!!
    val value = "1.5678"
    val roundedValue = "1.568"

    store.insertValue(deviceId, timeseriesId, value, Instant.EPOCH)

    val expected = listOf(TimeseriesValuesRow(timeseriesId, Instant.EPOCH, roundedValue))
    val actual = dslContext.selectFrom(TIMESERIES_VALUES).fetchInto(TimeseriesValuesRow::class.java)
    assertEquals(expected, actual)
  }

  @Test
  fun `insertValue does not round numeric value if timeseries does not specify decimal places`() {
    timeseriesRow.decimalPlaces = null
    timeseriesDao.insert(timeseriesRow)
    val timeseriesId = timeseriesRow.id!!
    val value = "1.56789999999999999999999"

    store.insertValue(deviceId, timeseriesId, value, Instant.EPOCH)

    val expected = listOf(TimeseriesValuesRow(timeseriesId, Instant.EPOCH, value))
    val actual = dslContext.selectFrom(TIMESERIES_VALUES).fetchInto(TimeseriesValuesRow::class.java)
    assertEquals(expected, actual)
  }

  @Test
  fun `insertValue throws DuplicateKeyException on duplicate timestamp`() {
    timeseriesDao.insert(timeseriesRow)
    val timeseriesId = timeseriesRow.id!!

    store.insertValue(deviceId, timeseriesId, "1", Instant.EPOCH)
    assertThrows<DuplicateKeyException> {
      store.insertValue(deviceId, timeseriesId, "2", Instant.EPOCH)
    }
  }

  @Test
  fun `insertValue throws exception if user has no permission to update timeseries`() {
    timeseriesDao.insert(timeseriesRow)

    every { user.canUpdateTimeseries(any()) } returns false

    assertThrows<AccessDeniedException> {
      store.insertValue(deviceId, timeseriesRow.id!!, "1", Instant.EPOCH)
    }
  }

  @Test
  fun `insertValue throws exception if user has no permission to read timeseries`() {
    timeseriesDao.insert(timeseriesRow)

    every { user.canReadTimeseries(any()) } returns false
    every { user.canUpdateTimeseries(any()) } returns false

    assertThrows<TimeseriesNotFoundException> {
      store.insertValue(deviceId, timeseriesRow.id!!, "1", Instant.EPOCH)
    }
  }

  @Test
  fun `insertValue throws exception if timeseries does not exist`() {
    assertThrows<Exception> { store.insertValue(deviceId, TimeseriesId(1), "1", Instant.EPOCH) }
  }

  @Test
  fun `checkExistingValues handles large lists of timestamps`() {
    timeseriesDao.insert(timeseriesRow)

    val timestamps = (0L..999L).map { Instant.ofEpochSecond(it) }
    val records = timestamps.map { TimeseriesValuesRecord(timeseriesRow.id, it, "1") }
    dslContext.insertInto(TIMESERIES_VALUES).set(records).execute()

    assertEquals(timestamps.toSet(), store.checkExistingValues(timeseriesRow.id!!, timestamps))
  }

  @Test
  fun `fetchByDeviceId throws exception if user has no permission to read timeseries`() {
    timeseriesDao.insert(timeseriesRow)

    every { user.canReadTimeseries(any()) } returns false

    assertThrows<TimeseriesNotFoundException> { store.fetchByDeviceId(deviceId) }
  }

  @Test
  fun `fetchByDeviceId returns latest value for timeseries with values`() {
    val time1 = Instant.EPOCH
    val time2 = time1 + Duration.ofMinutes(5)
    val timeseriesWithoutValues = timeseriesRow.copy(name = "second")

    timeseriesDao.insert(timeseriesRow)
    timeseriesDao.insert(timeseriesWithoutValues)

    // Insert the newer row first to increase the chances that they'd be returned in the wrong
    // order if the implementation code was missing an "ORDER BY" clause.
    dslContext
        .insertInto(
            TIMESERIES_VALUES,
            TIMESERIES_VALUES.TIMESERIES_ID,
            TIMESERIES_VALUES.CREATED_TIME,
            TIMESERIES_VALUES.VALUE,
        )
        .values(timeseriesRow.id, time2, "2")
        .values(timeseriesRow.id, time1, "1")
        .execute()

    val expected =
        listOf(
            TimeseriesModel(
                id = timeseriesWithoutValues.id!!,
                deviceId = deviceId,
                name = timeseriesWithoutValues.name!!,
                type = timeseriesWithoutValues.typeId!!,
                decimalPlaces = timeseriesWithoutValues.decimalPlaces,
                units = timeseriesWithoutValues.units,
                latestValue = null,
            ),
            TimeseriesModel(
                id = timeseriesRow.id!!,
                deviceId = deviceId,
                name = timeseriesRow.name!!,
                type = timeseriesRow.typeId!!,
                decimalPlaces = timeseriesRow.decimalPlaces,
                units = timeseriesRow.units,
                latestValue = TimeseriesValueModel(timeseriesRow.id!!, time2, "2"),
            ),
        )

    val actual = store.fetchByDeviceId(deviceId)
    assertEquals(expected, actual)
  }
}
