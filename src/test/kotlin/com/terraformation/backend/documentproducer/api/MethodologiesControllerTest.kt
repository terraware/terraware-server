package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class MethodologiesControllerTest : ControllerIntegrationTest() {
  private val path = "/api/v1/methodologies"

  @BeforeEach
  fun setUp() {
    insertUser()
  }

  @Nested
  inner class ListMethodologies {
    @Test
    fun `returns methodology details`() {
      val otherMethodologyId = insertMethodology(name = "Other Methodology")

      mockMvc
          .get(path)
          .andExpectJson(
              """
                {
                  "methodologies": [
                    {
                      "id": ${cannedMethodologyId},
                      "name": "Afforestation, Reforestation and Revegetation"
                    },
                    {
                      "id": $otherMethodologyId,
                      "name": "Other Methodology"
                    }
                  ]
                }"""
                  .trimIndent())
    }
  }
}
