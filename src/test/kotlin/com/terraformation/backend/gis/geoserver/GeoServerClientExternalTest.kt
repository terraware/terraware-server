package com.terraformation.backend.gis.geoserver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.dummyTerrawareServerConfig
import com.terraformation.backend.getEnvOrSkipTest
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GeoServerClientExternalTest {
  private lateinit var client: GeoServerClient

  @BeforeEach
  fun setUp() {
    val wfsUrl = URI.create(getEnvOrSkipTest("TERRAWARE_GEOSERVER_WFSURL"))
    val username = System.getenv("TERRAWARE_GEOSERVER_USERNAME")
    val password = System.getenv("TERRAWARE_GEOSERVER_PASSWORD")

    val config =
        dummyTerrawareServerConfig(
            geoServer =
                TerrawareServerConfig.GeoServerConfig(
                    password = password, username = username, wfsUrl = wfsUrl))

    client = GeoServerClient(config, jacksonObjectMapper().registerModule(GeometryModule()))
  }

  @Test
  fun `can get capabilities`() {
    val capabilities = client.getCapabilities()

    assertThat(capabilities.operations)
        .extracting("name")
        .containsAll(listOf("DescribeFeatureType", "GetFeature"))
  }
}
