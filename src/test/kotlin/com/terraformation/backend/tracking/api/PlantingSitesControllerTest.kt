package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.tracking.PlantingSiteId
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class PlantingSitesControllerTest : ControllerIntegrationTest() {
  private val svgMediaType = MediaType.valueOf("image/svg+xml")

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser()
  }

  @Test
  fun `downloads a map snapshot with the chosen format and attachment filename`() {
    val siteId = insertPlantingSite(areaHa = BigDecimal.TEN)
    val historyId = inserted.plantingSiteHistoryId
    insertStratum()
    insertSubstratum()
    val kmlMediaType = MediaType.valueOf("application/vnd.google-earth.kml+xml")
    val request =
        mockMvc
            .get("/api/v1/tracking/sites/$siteId/history/$historyId/download?format=Kml") {
              accept(kmlMediaType)
            }
            .andExpect { request { asyncStarted() } }
            .andReturn()

    val contentDisposition = "attachment; filename=\"planting-site-$siteId-version-$historyId.kml\""
    val response =
        mockMvc
            .perform(asyncDispatch(request))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", kmlMediaType.toString()))
            .andExpect(header().string("Content-Disposition", contentDisposition))
            .andReturn()
            .response
    assertThat(response.contentAsString).contains("<Folder><name>site-boundary</name>")
  }

  @Nested
  inner class DownloadCurrentPlantingSiteMap {
    @Test
    fun `downloads a live map without a history ID`() {
      val siteId = insertPlantingSite(areaHa = BigDecimal.TEN, insertHistory = false)
      insertStratum(insertHistory = false)
      insertSubstratum(insertHistory = false)
      val kmlMediaType = MediaType.valueOf("application/vnd.google-earth.kml+xml")
      val request =
          mockMvc
              .get("/api/v1/tracking/sites/$siteId/download?format=Kml") { accept(kmlMediaType) }
              .andExpect { request { asyncStarted() } }
              .andReturn()

      val response =
          mockMvc
              .perform(asyncDispatch(request))
              .andExpect(status().isOk)
              .andExpect(header().string("Content-Type", kmlMediaType.toString()))
              .andExpect(
                  header()
                      .string(
                          "Content-Disposition",
                          "attachment; filename=\"planting-site-$siteId.kml\"",
                      )
              )
              .andReturn()
              .response
      assertThat(response.contentAsString).contains("<Folder><name>site-boundary</name>")
    }

    @Test
    fun `responds with a conflict for missing live boundary or area`() {
      val missingBoundary = insertPlantingSite(x = null)
      val missingArea = insertPlantingSite()
      listOf(missingBoundary, missingArea).forEach { siteId ->
        mockMvc.get("/api/v1/tracking/sites/$siteId/download?format=GeoJson").andExpect {
          status { isConflict() }
        }
      }
    }
  }

  @Nested
  inner class GetPlantingSiteThumbnail {
    @Test
    fun `renders the site boundary`() {
      val plantingSiteId = insertPlantingSite()

      val body = renderThumbnail(plantingSiteId)

      assertTrue(body.startsWith("<svg "), "Response body: $body")
      assertTrue(body.contains("<path "), "Response body: $body")
    }

    @Test
    fun `honors requested dimensions`() {
      val plantingSiteId = insertPlantingSite()

      val body = renderThumbnail(plantingSiteId, "?width=64&height=32")

      assertTrue(body.contains("""viewBox="0 0 64 32""""), "Response body: $body")
    }

    @Test
    fun `returns conflict if site has no boundary`() {
      val plantingSiteId = insertPlantingSite(x = null)

      mockMvc
          .get("/api/v1/tracking/sites/$plantingSiteId/thumbnail") { accept(svgMediaType) }
          .andExpect { status { isConflict() } }
    }

    @Test
    fun `rejects dimensions outside the supported range`() {
      val plantingSiteId = insertPlantingSite()

      mockMvc
          .get("/api/v1/tracking/sites/$plantingSiteId/thumbnail?width=0") { accept(svgMediaType) }
          .andExpect { status { isBadRequest() } }

      mockMvc
          .get("/api/v1/tracking/sites/$plantingSiteId/thumbnail?height=4097") {
            accept(svgMediaType)
          }
          .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `cannot render a site in another organization`() {
      insertOrganization()
      val plantingSiteId = insertPlantingSite()

      mockMvc
          .get("/api/v1/tracking/sites/$plantingSiteId/thumbnail") { accept(svgMediaType) }
          .andExpect { status { isNotFound() } }
    }

    private fun renderThumbnail(plantingSiteId: PlantingSiteId, query: String = ""): String =
        mockMvc
            .get("/api/v1/tracking/sites/$plantingSiteId/thumbnail$query") { accept(svgMediaType) }
            .andExpect {
              status { isOk() }
              content { contentType(svgMediaType) }
            }
            .andReturn()
            .response
            .contentAsString
  }
}
