package com.terraformation.backend.eventlog.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.tracking.StratumId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.web.servlet.post

class EventLogControllerTest : ControllerIntegrationTest() {
  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser()
    insertPlantingSite()
    insertStratum()
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `returns empty history with permission to read the requested entities`(
      filterByStratum: Boolean
  ) {
    val stratumFilter = if (filterByStratum) ",\"stratumId\":${inserted.stratumId}" else ""

    mockMvc
        .post("/api/v1/events/list") {
          content = """{"organizationId":${inserted.organizationId}$stratumFilter}"""
        }
        .andExpectJson("""{"status":"ok","events":[]}""", strict = true)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `requires organization access even with access to the stratum`(filterByStratum: Boolean) {
    val stratumId = inserted.stratumId
    val inaccessibleOrganizationId = insertOrganization()
    val stratumFilter = if (filterByStratum) ",\"stratumId\":$stratumId" else ""

    mockMvc
        .post("/api/v1/events/list") {
          content = """{"organizationId":$inaccessibleOrganizationId$stratumFilter}"""
        }
        .andExpect { status { isNotFound() } }
  }

  @Test
  fun `requires access to the stratum's actual planting site`() {
    val readableOrganizationId = inserted.organizationId
    val readablePlantingSiteId = inserted.plantingSiteId
    insertOrganization()
    insertPlantingSite()
    val inaccessibleStratumId = insertStratum()

    mockMvc
        .post("/api/v1/events/list") {
          content =
              """{"organizationId":$readableOrganizationId,"plantingSiteId":$readablePlantingSiteId,"stratumId":$inaccessibleStratumId}"""
        }
        .andExpect { status { isNotFound() } }
  }

  @Test
  fun `returns not found for a nonexistent stratum`() {
    mockMvc
        .post("/api/v1/events/list") {
          content = """{"organizationId":${inserted.organizationId},"stratumId":${StratumId(-1)}}"""
        }
        .andExpect { status { isNotFound() } }
  }

  @Test
  fun `requires an organization ID even with a stratum filter`() {
    mockMvc
        .post("/api/v1/events/list") { content = """{"stratumId":${inserted.stratumId}}""" }
        .andExpect { status { isBadRequest() } }
  }
}
