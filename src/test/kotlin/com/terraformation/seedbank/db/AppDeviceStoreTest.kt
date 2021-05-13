package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.DeviceInfoPayload
import com.terraformation.seedbank.db.tables.daos.AppDeviceDao
import com.terraformation.seedbank.db.tables.pojos.AppDevice
import com.terraformation.seedbank.db.tables.references.APP_DEVICE
import com.terraformation.seedbank.model.AppDeviceModel
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AppDeviceStoreTest : DatabaseTest() {
  private val clock: Clock = mockk()

  private lateinit var appDeviceDao: AppDeviceDao
  private lateinit var store: AppDeviceStore

  override val sequencesToReset: List<String>
    get() = listOf("app_device_id_seq")

  @BeforeEach
  fun setup() {
    appDeviceDao = AppDeviceDao(dslContext.configuration())
    store = AppDeviceStore(dslContext, clock)

    every { clock.instant() } returns Instant.ofEpochMilli(50000)
  }

  /** Returns an AppDevice with default values for all the fields. */
  fun appDevice(
      id: Long? = null,
      appBuild: String? = "appBuild",
      appName: String? = "appName",
      brand: String? = "brand",
      createdTime: Instant = clock.instant(),
      model: String? = "model",
      name: String? = "name",
      osType: String? = "osType",
      osVersion: String? = "osVersion",
      uniqueId: String? = "uniqueId"
  ): AppDevice {
    return AppDevice(
        id, appBuild, appName, brand, createdTime, model, name, osType, osVersion, uniqueId)
  }

  @Test
  fun `getOrInsertDevice inserts new row if device does not exist`() {
    val payload =
        DeviceInfoPayload(
            "appBuild", "appName", "brand", "model", "name", "osType", "osVersion", "uniqueId")
    val id = store.getOrInsertDevice(payload.toModel())

    assertEquals(1, id, "New device ID")

    val expected = appDevice(id)
    val actual = appDeviceDao.fetchOneById(id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `getOrInsertDevice returns existing ID`() {
    val payload =
        DeviceInfoPayload(
            "appBuild", "appName", "brand", "model", "name", "osType", "osVersion", "uniqueId")

    appDeviceDao.insert(appDevice())
    assertNotNull(appDeviceDao.fetchOneById(1))

    val id = store.getOrInsertDevice(payload.toModel())
    assertEquals(1, id)
  }

  @Test
  fun `getOrInsertDevice matches null values`() {
    appDeviceDao.insert(AppDevice(createdTime = clock.instant()))
    assertNotNull(appDeviceDao.fetchOneById(1))

    val id = store.getOrInsertDevice(AppDeviceModel())

    val rows = dslContext.selectFrom(APP_DEVICE).fetch()
    println(rows)
    assertEquals(1, id)
  }

  @Test
  fun `fetchById returns null for null ID`() {
    assertNull(store.fetchById(null))
  }

  @Test
  fun `fetchById returns null for nonexistent ID`() {
    assertNull(store.fetchById(1))
  }

  @Test
  fun `fetchById fetches existing device`() {
    appDeviceDao.insert(appDevice())

    val expected =
        AppDeviceModel(
            1, "appBuild", "appName", "brand", "model", "name", "osType", "osVersion", "uniqueId")
    val actual = store.fetchById(1)
    assertEquals(expected, actual)
  }
}
