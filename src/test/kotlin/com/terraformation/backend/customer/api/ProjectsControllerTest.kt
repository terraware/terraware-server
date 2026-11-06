package com.terraformation.backend.customer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.default_schema.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.web.servlet.put

class ProjectsControllerTest : ControllerIntegrationTest() {
  @ParameterizedTest
  @ValueSource(strings = ["countryCode", "botanicalCountryCode", "both"])
  fun `omitted location fields clear their values`(omitted: String) {
    val botanicalCode = insertBotanicalCountry()
    insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    val projectId = insertProject(countryCode = "US", botanicalCountryCode = botanicalCode)
    val payload =
        when (omitted) {
          "countryCode" -> """{"botanicalCountryCode":"$botanicalCode"}"""
          "botanicalCountryCode" -> """{"countryCode":"AU"}"""
          else -> "{}"
        }

    mockMvc
        .put("/api/v1/projects/$projectId/location") { content = payload }
        .andExpectJson("""{"status":"ok"}""")

    val actual = projectsDao.fetchOneById(projectId)!!
    assertEquals(if (omitted == "botanicalCountryCode") "AU" else null, actual.countryCode)
    assertEquals(if (omitted == "countryCode") botanicalCode else null, actual.botanicalCountryCode)
  }
}
