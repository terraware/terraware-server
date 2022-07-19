package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.AppDeviceModel
import com.terraformation.backend.db.AppDeviceId
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.tables.pojos.AppDevicesRow
import com.terraformation.backend.db.tables.references.APP_DEVICES
import com.terraformation.backend.seedbank.api.DeviceInfoPayload
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AppDeviceStoreTest : DatabaseTest() {
  private val clock: Clock = mockk()

  private lateinit var store: AppDeviceStore

  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(APP_DEVICES)

  @BeforeEach
  fun setup() {
    store = AppDeviceStore(dslContext, clock)

    every { clock.instant() } returns Instant.ofEpochMilli(50000)
  }

  /** Returns an AppDevice with default values for all the fields. */
  fun appDevice(
      id: AppDeviceId? = null,
      appBuild: String? = "appBuild",
      appName: String? = "appName",
      brand: String? = "brand",
      createdTime: Instant = clock.instant(),
      model: String? = "model",
      name: String? = "name",
      osType: String? = "osType",
      osVersion: String? = "osVersion",
      uniqueId: String? = "uniqueId"
  ): AppDevicesRow {
    return AppDevicesRow(
        id, appBuild, appName, brand, createdTime, model, name, osType, osVersion, uniqueId)
  }

  @Test
  fun `getOrInsertDevice inserts new row if device does not exist`() {
    val payload =
        DeviceInfoPayload(
            "appBuild", "appName", "brand", "model", "name", "osType", "osVersion", "uniqueId")
    val id = store.getOrInsertDevice(payload.toModel())

    assertEquals(AppDeviceId(1), id, "New device ID")

    val expected = appDevice(id)
    val actual = appDevicesDao.fetchOneById(id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `getOrInsertDevice returns existing ID`() {
    val payload =
        DeviceInfoPayload(
            "appBuild", "appName", "brand", "model", "name", "osType", "osVersion", "uniqueId")

    appDevicesDao.insert(appDevice())
    assertNotNull(appDevicesDao.fetchOneById(AppDeviceId(1)))

    val id = store.getOrInsertDevice(payload.toModel())
    assertEquals(AppDeviceId(1), id)
  }

  @Test
  fun `getOrInsertDevice matches null values`() {
    appDevicesDao.insert(AppDevicesRow(createdTime = clock.instant()))
    assertNotNull(appDevicesDao.fetchOneById(AppDeviceId(1)))

    val id = store.getOrInsertDevice(AppDeviceModel())

    val rows = dslContext.selectFrom(APP_DEVICES).fetch()
    println(rows)
    assertEquals(AppDeviceId(1), id)
  }
}
