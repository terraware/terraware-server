package com.terraformation.backend.device.balena

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.BalenaDeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.log.perClassLogger
import io.mockk.every
import io.mockk.mockk
import java.net.http.HttpClient
import java.time.Instant
import kotlin.random.Random
import org.junit.AssumptionViolatedException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * End-to-end tests for the Balena client. This talks to the actual Balena API. To run it, you will
 * need a valid Balena API key. The key doesn't have to be associated with an organization and can
 * be created from a personal Balena account.
 *
 * Basic process for getting a key (note that Balena's UI may have changed since this was written):
 *
 * 1. Go to https://balena.io/ and click the "Sign up" button to sign up for an account.
 * 2. Once you're signed up and logged in, click your name in the navbar and click "Preferences."
 * 3. Click the "Access tokens" tab.
 * 4. Click "Create Api Key".
 * 5. Enter a name and optionally a description. These can be whatever you want.
 * 6. Click "Create token".
 * 7. Copy the API key somewhere secure.
 *
 * Once you have a key, put it in the `TEST_BALENA_API_KEY` environment variable and run this test.
 * (If you're running the test from IntelliJ, you can set environment variables in the Run
 * Configurations dialog.)
 *
 * The test will create a temporary fleet (application) and a dummy device when it starts, and will
 * destroy the device and the fleet when it finishes. This means that if you kill the test rather
 * than letting it finish, the fleet will still exist! You can delete it from the Balena dashboard.
 * Deleting the fleet from the dashboard should automatically delete the device as well.
 *
 * To shave a few seconds off the test's execution time, you can create a fleet in the Balena
 * console with a device type of "Raspberry Pi 4 (using 64bit OS)" and passing its numeric
 * identifier in the `TEST_BALENA_FLEET_ID` environment variable. _Don't use a fleet that's also
 * being used for real devices!_
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Run setup once per class, not once per method
internal class LiveBalenaClientTest {
  private val config: TerrawareServerConfig = mockk()
  private val log = perClassLogger()

  private lateinit var client: LiveBalenaClient

  private val fleetName = "test-${System.currentTimeMillis()}"
  private val shortCode = Random.nextInt(100000, 999999).toString()

  private lateinit var deviceId: BalenaDeviceId
  private var fleetId: Long = System.getenv("TEST_BALENA_FLEET_ID")?.toLongOrNull() ?: -1
  private var raspberryPiDeviceTypeId: Long = -1

  private var createdDevice: Boolean = false
  private var createdFleet: Boolean = false

  @BeforeAll
  fun setUp() {
    // Skip all the tests if no API key is available.
    val apiKey =
        System.getenv("TEST_BALENA_API_KEY") ?: throw AssumptionViolatedException("No API key")

    // Need to bootstrap the client without a fleet ID to create the fleet to define the config
    // that includes the fleet ID.
    every { config.balena } returns
        TerrawareServerConfig.BalenaConfig(apiKey = apiKey, enabled = true, fleetId = 0)

    client =
        LiveBalenaClient(
            config,
            HttpClient.newHttpClient(),
            jacksonObjectMapper().registerModule(JavaTimeModule()))

    raspberryPiDeviceTypeId = getDeviceTypeId()

    val fleetIdVar = System.getenv("TEST_BALENA_FLEET_ID")?.toLong()
    if (fleetIdVar != null) {
      fleetId = fleetIdVar
    } else {
      fleetId = createFleet(fleetName)
      createdFleet = true
    }

    deviceId = createDevice(shortCode)
    createdDevice = true

    every { config.balena } returns
        TerrawareServerConfig.BalenaConfig(apiKey = apiKey, enabled = true, fleetId = fleetId)
  }

  @AfterAll
  fun deleteFleet() {
    if (createdDevice) {
      log.info("Deleting device $deviceId")
      client.sendRequest<Unit>("${LiveBalenaClient.DEVICE_PATH}($deviceId)", method = "DELETE")
    }

    if (createdFleet && fleetId > -1) {
      log.info("Deleting fleet $fleetId")
      client.sendRequest<Unit>("${LiveBalenaClient.FLEET_PATH}($fleetId)", method = "DELETE")
      fleetId = -1
    }
  }

  @Test
  fun `getDeviceEnvironmentVar returns null if variable not set`() {
    Assertions.assertNull(client.getDeviceEnvironmentVar(deviceId, "nonexistent"))
  }

  @Test
  fun `getDeviceEnvironmentVar returns previously set value`() {
    val name = "newVar"
    val value = Random.nextLong().toString()

    client.setDeviceEnvironmentVar(deviceId, name, value)

    Assertions.assertEquals(value, client.getDeviceEnvironmentVar(deviceId, name))
  }

  @Test
  fun `getShortCodeForBalenaId returns short code`() {
    Assertions.assertEquals(shortCode, client.getShortCodeForBalenaId(deviceId))
  }

  @Test
  fun `setDeviceEnvironmentVar refuses to overwrite existing value if not requested`() {
    val name = "existingVar"
    client.setDeviceEnvironmentVar(deviceId, name, "x", overwrite = false)

    assertThrows<BalenaVariableExistsException> {
      client.setDeviceEnvironmentVar(deviceId, name, "y", overwrite = false)
    }
  }

  @Test
  fun `setDeviceEnvironmentVar overwrites existing value if requested`() {
    val name = "overwriteVar"

    client.setDeviceEnvironmentVar(deviceId, name, "old", false)
    client.setDeviceEnvironmentVar(deviceId, name, "new", true)

    Assertions.assertEquals("new", client.getDeviceEnvironmentVar(deviceId, name))
  }

  @Test
  fun `listModifiedDevices includes newly-created devices`() {
    val devices = client.listModifiedDevices(Instant.EPOCH)

    Assertions.assertTrue(devices.any { it.id == deviceId }, "Contains device created by test")
  }

  @Test
  fun `listModifiedDevices does not include devices that were not modified recently`() {
    val devices = client.listModifiedDevices(Instant.now())

    Assertions.assertEquals(emptyList<BalenaDevice>(), devices)
  }

  @Test
  fun `configureDeviceManager sets environment variables if not already set`() {
    deleteDeviceEnvironmentVar(LiveBalenaClient.FACILITIES_ENV_VAR_NAME)
    deleteDeviceEnvironmentVar(LiveBalenaClient.TOKEN_ENV_VAR_NAME)

    client.configureDeviceManager(deviceId, FacilityId(1), "access_token")

    Assertions.assertEquals(
        "1",
        client.getDeviceEnvironmentVar(deviceId, LiveBalenaClient.FACILITIES_ENV_VAR_NAME),
        "Facility ID after initial configuration")
    Assertions.assertEquals(
        "access_token",
        client.getDeviceEnvironmentVar(deviceId, LiveBalenaClient.TOKEN_ENV_VAR_NAME),
        "Refresh token after initial configuration")

    assertThrows<BalenaVariableExistsException> {
      client.configureDeviceManager(deviceId, FacilityId(2), "new_token")
    }

    Assertions.assertEquals(
        "1",
        client.getDeviceEnvironmentVar(deviceId, LiveBalenaClient.FACILITIES_ENV_VAR_NAME),
        "Facility ID after duplicate configuration attempt")
    Assertions.assertEquals(
        "access_token",
        client.getDeviceEnvironmentVar(deviceId, LiveBalenaClient.TOKEN_ENV_VAR_NAME),
        "Refresh token after duplicate configuration attempt")
  }

  @Test
  fun `configureDeviceManager succeeds when retrying if a previous attempt partially succeeded`() {
    client.setDeviceEnvironmentVar(deviceId, LiveBalenaClient.FACILITIES_ENV_VAR_NAME, "1")
    deleteDeviceEnvironmentVar(LiveBalenaClient.TOKEN_ENV_VAR_NAME)

    client.configureDeviceManager(deviceId, FacilityId(1), "access_token")

    Assertions.assertEquals(
        "1",
        client.getDeviceEnvironmentVar(deviceId, LiveBalenaClient.FACILITIES_ENV_VAR_NAME),
        "Facility ID after retry")
    Assertions.assertEquals(
        "access_token",
        client.getDeviceEnvironmentVar(deviceId, LiveBalenaClient.TOKEN_ENV_VAR_NAME),
        "Refresh token after retry")
  }

  private fun createFleet(fleetName: String): Long {
    val response =
        client.sendRequest<Map<String, Any?>>(
            LiveBalenaClient.FLEET_PATH,
            body = mapOf("app_name" to fleetName, "device_type" to RASPBERRY_PI_DEVICE_TYPE_SLUG))
    val fleetId = response.body().get()["id"].toString().toLong()

    log.info("Created fleet $fleetId")

    return fleetId
  }

  private fun getDeviceTypeId(): Long {
    val response = client.sendRequest<GetDeviceTypesResponse>(LiveBalenaClient.DEVICE_TYPE_PATH)
    return response.body().get().d.first { it.slug == RASPBERRY_PI_DEVICE_TYPE_SLUG }.id
  }

  private fun createDevice(shortCode: String): BalenaDeviceId {
    val response =
        client.sendRequest<BalenaDevice>(
            LiveBalenaClient.DEVICE_PATH,
            body =
                mapOf(
                    "belongs_to__application" to fleetId,
                    "is_of__device_type" to raspberryPiDeviceTypeId))

    val deviceId = response.body().get().id
    log.info("Created device $deviceId")

    client.sendRequest<Unit>(
        LiveBalenaClient.DEVICE_TAG_PATH,
        body =
            mapOf(
                "device" to deviceId,
                "tag_key" to LiveBalenaClient.SHORT_CODE_TAG_KEY,
                "value" to shortCode))

    return deviceId
  }

  private fun deleteDeviceEnvironmentVar(name: String) {
    val envVarId = client.getDeviceEnvironmentVarId(deviceId, name)
    if (envVarId != null) {
      client.sendRequest<Unit>(
          "${LiveBalenaClient.DEVICE_ENV_VAR_PATH}($envVarId)", method = "DELETE")
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class GetDeviceTypesResponse(val d: List<DeviceType>) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DeviceType(val id: Long, val slug: String)
  }

  companion object {
    private const val RASPBERRY_PI_DEVICE_TYPE_SLUG = "raspberrypi4-64"
  }
}
