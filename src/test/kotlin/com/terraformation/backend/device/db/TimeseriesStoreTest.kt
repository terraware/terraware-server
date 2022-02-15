package com.terraformation.backend.device.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.TimeseriesId
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.TimeseriesType
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.TimeseriesDao
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.db.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.tables.pojos.TimeseriesValuesRow
import com.terraformation.backend.db.tables.references.TIMESERIES_VALUES
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
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

  private val clock: Clock = mockk()
  private lateinit var devicesDao: DevicesDao
  private lateinit var store: TimeseriesStore
  private lateinit var timeseriesDao: TimeseriesDao

  private val deviceId = DeviceId(1)
  private val facilityId = FacilityId(100)

  private lateinit var timeseriesRow: TimeseriesRow

  @BeforeEach
  fun setUp() {
    devicesDao = DevicesDao(dslContext.configuration())
    timeseriesDao = TimeseriesDao(dslContext.configuration())

    store = TimeseriesStore(clock, dslContext)

    insertSiteData()
    devicesDao.insert(
        DevicesRow(
            address = "address",
            createdBy = user.userId,
            deviceType = "type",
            facilityId = facilityId,
            id = deviceId,
            make = "make",
            model = "model",
            modifiedBy = user.userId,
            name = "device",
            protocol = "protocol",
        ))

    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreateTimeseries(any()) } returns true
    every { user.canReadDevice(any()) } returns true
    every { user.canReadTimeseries(any()) } returns true
    every { user.canUpdateTimeseries(any()) } returns true

    timeseriesRow =
        TimeseriesRow(
            createdBy = user.userId,
            createdTime = clock.instant(),
            decimalPlaces = 2,
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

    val actual = store.fetchOneByName(deviceId, timeseriesRow.name!!)
    assertEquals(timeseriesRow, actual)
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
    val otherUser = mockUser(UserId(3))
    insertUser(otherUser.userId)
    every { otherUser.canCreateTimeseries(any()) } returns true

    timeseriesDao.insert(timeseriesRow)
    val timeseriesId = timeseriesRow.id!!

    every { clock.instant() } returns Instant.EPOCH.plusSeconds(60)

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
}
