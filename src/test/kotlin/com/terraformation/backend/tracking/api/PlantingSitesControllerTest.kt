package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.tracking.PlantingSiteId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get

class PlantingSitesControllerTest : ControllerIntegrationTest() {
  private val svgMediaType = MediaType.valueOf("image/svg+xml")

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser()
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
