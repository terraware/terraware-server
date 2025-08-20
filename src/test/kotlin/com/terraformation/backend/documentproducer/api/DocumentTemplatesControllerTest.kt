package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class DocumentTemplatesControllerTest : ControllerIntegrationTest() {
  private val path = "/api/v1/document-producer/templates"

  @Nested
  inner class ListDocumentTemplates {
    @Test
    fun `returns document template details`() {
      val documentTemplateId = insertDocumentTemplate(name = "Feasibility Study")
      insertVariableManifest(documentTemplateId = documentTemplateId)
      val otherDocumentTemplateId = insertDocumentTemplate(name = "Other Document Template")

      mockMvc
          .get(path)
          .andExpectJson(
              """
                {
                  "documentTemplates": [
                    {
                      "id": $documentTemplateId,
                      "name": "Feasibility Study",
                      "variableManifestId": ${inserted.variableManifestId}
                    },
                    {
                      "id": $otherDocumentTemplateId,
                      "name": "Other Document Template"
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )
    }
  }
}
