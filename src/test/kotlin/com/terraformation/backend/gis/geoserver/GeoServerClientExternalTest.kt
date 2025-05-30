package com.terraformation.backend.gis.geoserver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.dummyTerrawareServerConfig
import com.terraformation.backend.getEnvOrSkipTest
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.geojson.feature.FeatureJSON
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull

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

  @Test
  fun `can get features`() {
    val features = client.getPlantingSiteFeatures("tf_accelerator:project_no=2", null)
    val iter = features.features()
    val fj = FeatureJSON()
    while (iter.hasNext()) {
      fj.writeFeature(iter.next() as SimpleFeature, System.out)
      println()
    }
    assertNull(features.features())
  }
}
