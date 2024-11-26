package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

class ObservationControllerTest : ControllerIntegrationTest() {
  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
  }

  @Nested
  inner class CompleteAdHocPlotMonitoringObservation {
    private val path = "/api/v1/tracking/observations/ad-hoc/monitoring"

    @Test
    fun `Can create the monitoring plot and observation with all associated data`() {
      val payload =
          """
            {
              "plantingSiteId": "1",
            }
          """
              .trimIndent()

      mockMvc.post(path) { content = payload }.andExpect { status { is2xxSuccessful() } }
    }
  }
}
