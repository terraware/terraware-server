package com.terraformation.backend.device

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.AutomationModel.Companion.DEVICE_ID_KEY
import com.terraformation.backend.customer.model.AutomationModel.Companion.LOWER_THRESHOLD_KEY
import com.terraformation.backend.customer.model.AutomationModel.Companion.SENSOR_BOUNDS_TYPE
import com.terraformation.backend.customer.model.AutomationModel.Companion.TIMESERIES_NAME_KEY
import com.terraformation.backend.customer.model.AutomationModel.Companion.TYPE_KEY
import com.terraformation.backend.customer.model.AutomationModel.Companion.UPPER_THRESHOLD_KEY
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceTemplateCategory
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.tables.pojos.AutomationsRow
import com.terraformation.backend.db.tables.pojos.DeviceTemplatesRow
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.mockUser
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException

internal class DeviceServiceTest : DatabaseTest(), RunsAsUser {
  override val tablesToResetSequences = listOf(DEVICES)
  override val user: TerrawareUser = mockUser()

  private val clock: Clock = mockk()
  private val eventPublisher: ApplicationEventPublisher = mockk()
  private val objectMapper = jacksonObjectMapper()
  private val service: DeviceService by lazy {
    DeviceService(
        AutomationStore(automationsDao, clock, dslContext, objectMapper, ParentStore(dslContext)),
        DeviceStore(devicesDao),
        deviceTemplatesDao,
        dslContext,
        eventPublisher,
        FacilityStore(clock, dslContext, facilitiesDao, storageLocationsDao),
    )
  }

  private val facilityId = FacilityId(100)

  private val bmuRow =
      DevicesRow(
          facilityId = facilityId,
          name = "PV",
          deviceType = "BMU",
          make = "Blue Ion",
          model = "LV",
      )

  private val omniSenseRow =
      DevicesRow(
          facilityId = facilityId,
          deviceType = "sensor",
          name = "0123ABCD",
          make = "OmniSense",
          model = "S-11",
      )

  /** Expected temperature and humidity ranges for temperature sensors. */
  data class OmniSense(
      val name: String,
      private val temperature: Pair<Double, Double>? = null,
      private val humidity: Pair<Double, Double>? = null,
  ) {
    fun toAutomationConfigs(deviceId: DeviceId): Map<String, Map<String, Any?>> {
      return listOfNotNull(
              humidity?.let { (min, max) ->
                "$name humidity" to
                    mapOf(
                        TYPE_KEY to SENSOR_BOUNDS_TYPE,
                        DEVICE_ID_KEY to deviceId.value.toInt(),
                        TIMESERIES_NAME_KEY to "humidity",
                        LOWER_THRESHOLD_KEY to min,
                        UPPER_THRESHOLD_KEY to max,
                    )
              },
              temperature?.let { (min, max) ->
                "$name temperature" to
                    mapOf(
                        TYPE_KEY to SENSOR_BOUNDS_TYPE,
                        DEVICE_ID_KEY to deviceId.value.toInt(),
                        TIMESERIES_NAME_KEY to "temperature",
                        LOWER_THRESHOLD_KEY to min,
                        UPPER_THRESHOLD_KEY to max,
                    )
              },
          )
          .toMap()
    }
  }

  companion object {
    @JvmStatic
    val knownSensors =
        listOf(
            OmniSense("Ambient 1", 21.0 to 25.0, 34.0 to 40.0),
            OmniSense("Dry Cabinet 1", 21.0 to 25.0, 27.0 to 33.0),
            OmniSense("Freezer 3", -25.0 to -15.0),
            OmniSense("Fridge 2", 0.0 to 10.0))
  }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreateAutomation(any()) } returns true
    every { user.canCreateDevice(any()) } returns true
    every { user.canDeleteAutomation(any()) } returns true
    every { user.canListAutomations(any()) } returns true
    every { user.canReadAutomation(any()) } returns true
    every { user.canReadDevice(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canUpdateDevice(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `create does not add automations if device is of unrecognized type`() {
    service.create(
        DevicesRow(
            facilityId = facilityId,
            name = "test",
            deviceType = "random",
            make = "company",
            model = "product"))
    assertEquals(emptyList<AutomationsRow>(), automationsDao.findAll())
  }

  @Test
  fun `create adds BMU automations`() {
    val deviceId = service.create(bmuRow)

    assertHasBmuAutomations(deviceId)
  }

  @Test
  fun `update adds BMU automations`() {
    val deviceId =
        service.create(
            DevicesRow(
                facilityId = facilityId,
                name = "PV",
                deviceType = "unknown",
                make = "unknown",
                model = "unknown"))

    service.update(bmuRow.copy(id = deviceId))

    assertHasBmuAutomations(deviceId)
  }

  @Test
  fun `create does not create automations for sensor with unrecognized name`() {
    service.create(omniSenseRow)

    assertAutomationConfigsEqual(emptyMap())
  }

  @MethodSource("getKnownSensors")
  @ParameterizedTest(name = "{0}")
  fun `create adds automations for recognized sensor names`(omniSense: OmniSense) {
    val deviceId = service.create(omniSenseRow.copy(name = omniSense.name))

    assertAutomationConfigsEqual(omniSense.toAutomationConfigs(deviceId))
  }

  @MethodSource("getKnownSensors")
  @ParameterizedTest(name = "{0}")
  fun `update adds automations for recognized sensor names`(omniSense: OmniSense) {
    val deviceId = service.create(omniSenseRow)
    service.update(omniSenseRow.copy(id = deviceId, name = omniSense.name))

    assertAutomationConfigsEqual(omniSense.toAutomationConfigs(deviceId))
  }

  @Test
  fun `update removes automations if recognized sensor name is removed`() {
    val deviceId = service.create(omniSenseRow.copy(name = "Fridge 1"))
    service.update(omniSenseRow.copy(id = deviceId))

    assertAutomationConfigsEqual(emptyMap())
  }

  @Test
  fun `update switches automations if sensor is renamed from one recognized name to another`() {
    val ambientBounds = knownSensors.first { it.name.startsWith("Ambient") }
    val fridgeBounds = knownSensors.first { it.name.startsWith("Fridge") }

    val deviceId = service.create(omniSenseRow.copy(name = fridgeBounds.name))
    service.update(omniSenseRow.copy(id = deviceId, name = ambientBounds.name))

    assertAutomationConfigsEqual(ambientBounds.toAutomationConfigs(deviceId))
  }

  @Test
  fun `create does not create device if no permission to create automations`() {
    every { user.canCreateAutomation(any()) } returns false

    assertThrows<AccessDeniedException> { service.create(omniSenseRow.copy(name = "Fridge 1")) }

    assertEquals(emptyList<DevicesRow>(), devicesDao.findAll())
    assertAutomationConfigsEqual(emptyMap())
  }

  @Test
  fun `update does not update device if no permission to create automations`() {
    every { user.canCreateAutomation(any()) } returns false

    val deviceId = service.create(omniSenseRow)
    val expectedDevices = devicesDao.findAll()

    assertThrows<AccessDeniedException> {
      service.update(omniSenseRow.copy(id = deviceId, name = "Fridge 1"))
    }

    assertEquals(expectedDevices, devicesDao.findAll())
    assertAutomationConfigsEqual(emptyMap())
  }

  @Test
  fun `markDeviceUnresponsive publishes event`() {
    val slot = CapturingSlot<DeviceUnresponsiveEvent>()

    every { eventPublisher.publishEvent(capture(slot)) } just Runs

    val expected =
        DeviceUnresponsiveEvent(DeviceId(1), Instant.ofEpochSecond(123), Duration.ofSeconds(30))

    service.markUnresponsive(
        expected.deviceId, expected.lastRespondedTime, expected.expectedInterval)

    assertEquals(expected, slot.captured)
  }

  @Test
  fun `markDeviceUnresponsive throws exception if no permission to update device`() {
    every { user.canUpdateDevice(any()) } returns false

    assertThrows<AccessDeniedException> { service.markUnresponsive(DeviceId(1), null, null) }
  }

  private fun assertHasBmuAutomations(deviceId: DeviceId) {
    val commonFields =
        mapOf(
            TYPE_KEY to SENSOR_BOUNDS_TYPE,
            DEVICE_ID_KEY to deviceId.value.toInt(),
            TIMESERIES_NAME_KEY to "relative_state_of_charge",
        )

    val expected =
        mapOf(
            "PV 25% charge" to commonFields + mapOf(LOWER_THRESHOLD_KEY to 25.1),
            "PV 10% charge" to commonFields + mapOf(LOWER_THRESHOLD_KEY to 10.1),
            "PV 0% charge" to commonFields + mapOf(LOWER_THRESHOLD_KEY to 0.1),
        )

    assertAutomationConfigsEqual(expected)
  }

  @Test
  fun `createDefaultDevices does nothing if facility has no default template category`() {
    val desalFacilityId = FacilityId(2)
    insertFacility(desalFacilityId, siteId = 10, type = FacilityType.Desalination)
    deviceTemplatesDao.insert(
        DeviceTemplatesRow(
            categoryId = DeviceTemplateCategory.SeedBankDefault,
            deviceType = "type",
            name = "name",
            make = "make",
            model = "model"))

    service.createDefaultDevices(desalFacilityId)

    assertEquals(emptyList<DevicesRow>(), devicesDao.findAll())
  }

  @Test
  fun `createDefaultDevices creates seed bank devices from templates`() {
    val template =
        DeviceTemplatesRow(
            address = "address",
            categoryId = DeviceTemplateCategory.SeedBankDefault,
            deviceType = "type",
            make = "make",
            model = "model",
            name = "name",
            pollingInterval = 50,
            port = 123,
            protocol = "protocol",
            settings = JSONB.jsonb("{\"data\":\"abc\"}"),
        )

    deviceTemplatesDao.insert(template)

    service.createDefaultDevices(facilityId)

    val expected =
        listOf(
            DevicesRow(
                address = template.address,
                createdBy = user.userId,
                deviceType = template.deviceType,
                enabled = true,
                facilityId = facilityId,
                id = DeviceId(1),
                make = template.make,
                model = template.model,
                modifiedBy = user.userId,
                name = template.name,
                pollingInterval = template.pollingInterval,
                port = template.port,
                protocol = template.protocol,
                settings = template.settings,
            ))

    val actual = devicesDao.findAll()
    assertEquals(expected, actual)
  }

  private fun assertAutomationConfigsEqual(expected: Map<String, Map<String, Any?>>) {
    val actual =
        automationsDao
            .findAll()
            .mapNotNull { row ->
              row.configuration?.let { configuration ->
                row.name to objectMapper.readValue<Map<String, Any?>>(configuration.data())
              }
            }
            .toMap()

    assertEquals(expected, actual)
  }
}
