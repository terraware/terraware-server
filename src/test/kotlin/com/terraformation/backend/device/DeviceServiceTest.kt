package com.terraformation.backend.device

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.AutomationModel.Companion.SENSOR_BOUNDS_TYPE
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceTemplateCategory
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.tables.pojos.AutomationsRow
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceTemplatesRow
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.security.access.AccessDeniedException
import tools.jackson.module.kotlin.jacksonObjectMapper

internal class DeviceServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val objectMapper = jacksonObjectMapper()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val service: DeviceService by lazy {
    DeviceService(
        AutomationStore(automationsDao, clock, dslContext, objectMapper, parentStore),
        DeviceStore(devicesDao),
        deviceTemplatesDao,
        dslContext,
        eventPublisher,
        FacilityStore(
            clock,
            mockk(),
            dslContext,
            eventPublisher,
            facilitiesDao,
            Messages(),
            organizationsDao,
            subLocationsDao,
        ),
    )
  }

  private lateinit var facilityId: FacilityId

  private val bmuRow: DevicesRow by lazy {
    DevicesRow(
        facilityId = facilityId,
        name = "PV",
        deviceType = "BMU",
        make = "Blue Ion",
        model = "LV",
    )
  }

  private val omniSenseRow: DevicesRow by lazy {
    DevicesRow(
        facilityId = facilityId,
        deviceType = "sensor",
        name = "0123ABCD",
        make = "OmniSense",
        model = "S-11",
    )
  }

  /** Expected temperature and humidity ranges for temperature sensors. */
  data class OmniSense(
      val name: String,
      private val temperature: Pair<Double, Double>? = null,
      private val humidity: Pair<Double, Double>? = null,
  ) {
    fun toAutomationConfigs(deviceId: DeviceId): Set<AutomationsRow> {
      return setOfNotNull(
          humidity?.let { (min, max) ->
            AutomationsRow(
                deviceId = deviceId,
                name = "$name humidity",
                type = SENSOR_BOUNDS_TYPE,
                timeseriesName = "humidity",
                lowerThreshold = min,
                upperThreshold = max,
                verbosity = 0,
            )
          },
          temperature?.let { (min, max) ->
            AutomationsRow(
                deviceId = deviceId,
                name = "$name temperature",
                type = SENSOR_BOUNDS_TYPE,
                timeseriesName = "temperature",
                lowerThreshold = min,
                upperThreshold = max,
                verbosity = 0,
            )
          },
      )
    }
  }

  companion object {
    @JvmStatic
    val knownSensors =
        listOf(
            OmniSense("Ambient 1", 21.0 to 25.0, 34.0 to 40.0),
            OmniSense("Dry Cabinet 1", 21.0 to 25.0, 27.0 to 33.0),
            OmniSense("Freezer 3", -25.0 to -15.0),
            OmniSense("Fridge 2", 0.0 to 10.0),
        )
  }

  @BeforeEach
  fun setUp() {
    every { user.canCreateAutomation(any()) } returns true
    every { user.canCreateDevice(any()) } returns true
    every { user.canDeleteAutomation(any()) } returns true
    every { user.canListAutomations(any()) } returns true
    every { user.canReadAutomation(any()) } returns true
    every { user.canReadDevice(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canUpdateDevice(any()) } returns true

    insertOrganization()
    facilityId = insertFacility()
  }

  @Test
  fun `create does not add automations if device is of unrecognized type`() {
    service.create(
        DevicesRow(
            facilityId = facilityId,
            name = "test",
            deviceType = "random",
            make = "company",
            model = "product",
        )
    )
    assertTableEmpty(AUTOMATIONS)
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
                model = "unknown",
            )
        )

    service.update(bmuRow.copy(id = deviceId))

    assertHasBmuAutomations(deviceId)
  }

  @Test
  fun `create does not create automations for sensor with unrecognized name`() {
    service.create(omniSenseRow)

    assertAutomationConfigsEqual(emptySet())
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

    assertAutomationConfigsEqual(emptySet())
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

    assertTableEmpty(DEVICES)
    assertAutomationConfigsEqual(emptySet())
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
    assertAutomationConfigsEqual(emptySet())
  }

  @Test
  fun `markDeviceUnresponsive publishes event`() {
    val expected =
        DeviceUnresponsiveEvent(DeviceId(1), Instant.ofEpochSecond(123), Duration.ofSeconds(30))

    service.markUnresponsive(
        expected.deviceId,
        expected.lastRespondedTime,
        expected.expectedInterval,
    )

    eventPublisher.assertEventPublished(expected)
  }

  @Test
  fun `markDeviceUnresponsive throws exception if no permission to update device`() {
    every { user.canUpdateDevice(any()) } returns false

    assertThrows<AccessDeniedException> { service.markUnresponsive(DeviceId(1), null, null) }
  }

  private fun assertHasBmuAutomations(deviceId: DeviceId) {
    val expected =
        listOf(
                "PV 25% charge" to 25.1,
                "PV 10% charge" to 10.1,
                "PV 0% charge" to 0.1,
            )
            .map {
              AutomationsRow(
                  deviceId = deviceId,
                  facilityId = facilityId,
                  name = it.first,
                  lowerThreshold = it.second,
                  timeseriesName = "relative_state_of_charge",
                  type = SENSOR_BOUNDS_TYPE,
                  verbosity = 0,
              )
            }

    assertAutomationConfigsEqual(expected)
  }

  @Test
  fun `createDefaultDevices does nothing if facility has no default template category`() {
    val desalFacilityId = insertFacility(type = FacilityType.Desalination)
    deviceTemplatesDao.insert(
        DeviceTemplatesRow(
            categoryId = DeviceTemplateCategory.SeedBankDefault,
            deviceType = "type",
            name = "name",
            make = "make",
            model = "model",
        )
    )

    service.createDefaultDevices(desalFacilityId)

    assertTableEmpty(DEVICES)
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
            port = 123,
            protocol = "protocol",
            settings = JSONB.jsonb("{\"data\":\"abc\"}"),
            verbosity = 3,
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
                make = template.make,
                model = template.model,
                modifiedBy = user.userId,
                name = template.name,
                port = template.port,
                protocol = template.protocol,
                settings = template.settings,
                verbosity = template.verbosity,
            )
        )

    val actual = devicesDao.findAll().map { it.copy(id = null) }
    assertEquals(expected, actual)
  }

  private fun assertAutomationConfigsEqual(expected: Collection<AutomationsRow>) {
    val expectedWithTimes =
        expected
            .map {
              it.copy(
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  facilityId = facilityId,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
              )
            }
            .toSet()

    val actual = automationsDao.findAll().map { it.copy(id = null) }.toSet()

    assertEquals(expectedWithTimes, actual)
  }
}
