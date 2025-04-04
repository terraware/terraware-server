package com.terraformation.backend.tracking.mapbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.SRID
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.mapbox.MapboxService
import com.terraformation.backend.util.HttpClientConfig
import io.ktor.client.engine.java.Java
import io.mockk.every
import java.net.URI
import org.junit.Assume.assumeNotNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import org.springframework.boot.context.properties.EnableConfigurationProperties

@EnableConfigurationProperties(TerrawareServerConfig::class)
class MapboxServiceExternalTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private lateinit var service: MapboxService

  @BeforeEach
  fun setUp() {
    every { user.canDeleteSupportIssue() } returns true
    val apiToken = getEnvOrSkipTest("TERRAWARE_MAPBOX_APITOKEN")

    val config =
        TerrawareServerConfig(
            webAppUrl = URI("https://terraware.io"),
            keycloak =
                TerrawareServerConfig.KeycloakConfig(
                    apiClientId = "test",
                    apiClientGroupName = "test",
                    apiClientUsernamePrefix = "test"),
            mapbox =
                TerrawareServerConfig.MapboxConfig(
                    apiToken = apiToken,
                    temporaryTokenExpirationMinutes = 30L,
                ))

    val httpClient = HttpClientConfig().httpClient(Java.create(), jacksonObjectMapper())
    service = MapboxService(config, httpClient)
  }

  @MethodSource("elevationTestData")
  @ParameterizedTest
  fun `can fetch elevation data`(elevationDataPoint: ElevationDataPoint) {
    val actual = service.getElevation(elevationDataPoint.point)
    assertApproximatelyEqual(
        elevationDataPoint.elevation, actual, 0.5, "Fetching elevation for $elevationDataPoint")
  }

  private fun getEnvOrSkipTest(name: String): String {
    val value = System.getenv(name)
    assumeNotNull(value, "$name not set; skipping test")
    return value
  }

  data class ElevationDataPoint(val name: String, val point: Point, val elevation: Double) {
    override fun toString(): String {
      return "$name, Latitude: ${point.y}, Longitude: ${point.x}"
    }
  }

  private fun assertApproximatelyEqual(
      expected: Double,
      actual: Double,
      percentThreshold: Double = 1.0,
      message: String = ""
  ) {
    val difference = Math.abs(actual - expected)
    val relativeDifference = difference / Math.abs(expected) * 100
    if (relativeDifference > percentThreshold) {
      assertEquals(expected, actual, message)
    }
  }

  companion object {

    // https://latlongdata.com/elevation/
    @JvmStatic
    fun elevationTestData(): List<ElevationDataPoint> {
      val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
      return listOf(
          ElevationDataPoint(
              "Mount Everest", geometryFactory.createPoint(Coordinate(86.9249, 27.9881)), 8730.0),
          ElevationDataPoint(
              "Death Valley", geometryFactory.createPoint(Coordinate(-116.9325, 36.5323)), -80.8),
          ElevationDataPoint(
              "Grand Canyon", geometryFactory.createPoint(Coordinate(-112.1124, 36.0998)), 733.0),
          ElevationDataPoint(
              "Kilimanjaro", geometryFactory.createPoint(Coordinate(37.3556, -3.0674)), 5826.3),
          ElevationDataPoint(
              "Mount Whitney", geometryFactory.createPoint(Coordinate(-118.2922, 36.5785)), 4412.4),
          ElevationDataPoint(
              "Sahara Desert", geometryFactory.createPoint(Coordinate(25.6628, 23.4162)), 982.6))
    }
  }
}
