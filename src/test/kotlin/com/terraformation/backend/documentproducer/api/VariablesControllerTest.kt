package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class VariablesControllerTest : ControllerIntegrationTest() {
  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
  }

  @Nested
  inner class ListVariables {
    private fun path(manifestId: VariableManifestId = inserted.variableManifestId) =
        "/api/v1/document-producer/variables?manifestId=$manifestId"

    @Test
    fun `only lists variables for requested manifest`() {
      val manifestId1 = insertVariableManifest()
      val manifestId2 = insertVariableManifest()
      val variableId1 = insertVariable(name = "Date Variable", type = VariableType.Date)
      val variableId2 = insertVariable(name = "Link Variable", type = VariableType.Link)

      insertVariableManifestEntry(manifestId = manifestId1, variableId = variableId1, position = 0)
      insertVariableManifestEntry(manifestId = manifestId2, variableId = variableId2, position = 0)

      mockMvc
          .get(path(manifestId1))
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "id": $variableId1,
                      "name": "Date Variable",
                      "position": 0,
                      "type": "Date",
                      "isList": false
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true)
    }

    @Test
    fun `represents section recommendations reciprocally`() {
      val sectionVariableId =
          insertVariableManifestEntry(
              insertSectionVariable(
                  id = insertVariable(name = "Section Variable", type = VariableType.Section)))
      val dateVariableId =
          insertVariableManifestEntry(
              insertVariable(name = "Date Variable", type = VariableType.Date))

      insertSectionRecommendation(sectionId = sectionVariableId, recommendedId = dateVariableId)

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "children": [],
                      "id": $sectionVariableId,
                      "name": "Section Variable",
                      "position": 0,
                      "recommends": [$dateVariableId],
                      "renderHeading": true,
                      "type": "Section",
                      "isList": true
                    },
                    {
                      "id": $dateVariableId,
                      "name": "Date Variable",
                      "position": 1,
                      "recommendedBy": [$sectionVariableId],
                      "type": "Date",
                      "isList": false
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true)
    }

    @Test
    fun `uses correct payload for each variable type`() {
      val dateVariableId =
          insertVariableManifestEntry(
              insertVariable(
                  type = VariableType.Date,
                  isList = true,
                  name = "A date",
                  description = "A description"))
      val imageVariableId =
          insertVariableManifestEntry(insertVariable(type = VariableType.Image, name = "An image"))
      val linkVariableId =
          insertVariableManifestEntry(insertVariable(type = VariableType.Link, name = "A link"))
      val numberVariableId =
          insertVariableManifestEntry(
              insertNumberVariable(
                  id = insertVariable(type = VariableType.Number, name = "A number"),
                  decimalPlaces = 1,
                  minValue = BigDecimal.TWO,
                  maxValue = BigDecimal.TEN))
      val parentSectionVariableId =
          insertVariableManifestEntry(
              insertSectionVariable(
                  id = insertVariable(type = VariableType.Section, name = "Parent section")))
      val childSectionVariableId =
          insertVariableManifestEntry(
              insertSectionVariable(
                  id = insertVariable(type = VariableType.Section, name = "Child section"),
                  parentId = parentSectionVariableId,
                  renderHeading = false))
      val selectVariableId =
          insertVariableManifestEntry(
              insertSelectVariable(
                  id = insertVariable(type = VariableType.Select, name = "A select"),
                  isMultiple = true))
      val optionId1 =
          insertSelectOption(
              selectVariableId,
              "Option 1",
              description = "Description 1",
              renderedText = "Rendered 1")
      val optionId2 = insertSelectOption(selectVariableId, "Option 2")
      val tableVariableId =
          insertVariableManifestEntry(
              insertTableVariable(id = insertVariable(type = VariableType.Table, name = "A table")))
      val columnId1 =
          insertTableColumn(
              tableVariableId,
              insertVariableManifestEntry(
                  insertVariable(name = "Column 1", type = VariableType.Date)),
              isHeader = true)
      val columnId2 =
          insertVariableManifestEntry(
              insertTableColumn(
                  tableVariableId, insertVariable(name = "Column 2", type = VariableType.Link)))
      val textVariableId =
          insertVariableManifestEntry(
              insertTextVariable(
                  id = insertVariable(type = VariableType.Text, name = "A paragraph"),
                  textType = VariableTextType.MultiLine))

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "id": $dateVariableId,
                      "name": "A date",
                      "description": "A description",
                      "position": 0,
                      "type": "Date",
                      "isList": true
                    },
                    {
                      "id": $imageVariableId,
                      "name": "An image",
                      "position": 1,
                      "type": "Image",
                      "isList": false
                    },
                    {
                      "id": $linkVariableId,
                      "name": "A link",
                      "position": 2,
                      "type": "Link",
                      "isList": false
                    },
                    {
                      "id": $numberVariableId,
                      "name": "A number",
                      "position": 3,
                      "type": "Number",
                      "isList": false,
                      "decimalPlaces": 1,
                      "minValue": 2,
                      "maxValue": 10
                    },
                    {
                      "id": $parentSectionVariableId,
                      "name": "Parent section",
                      "position": 4,
                      "type": "Section",
                      "isList": true,
                      "renderHeading": true,
                      "recommends": [],
                      "children": [
                        {
                          "id": $childSectionVariableId,
                          "name": "Child section",
                          "position": 5,
                          "type": "Section",
                          "isList": true,
                          "renderHeading": false,
                          "recommends": [],
                          "children": []
                        }
                      ]
                    },
                    {
                      "id": $selectVariableId,
                      "name": "A select",
                      "position": 6,
                      "type": "Select",
                      "isList": false,
                      "isMultiple": true,
                      "options": [
                        {
                          "id": $optionId1,
                          "name": "Option 1",
                          "description": "Description 1",
                          "renderedText": "Rendered 1"
                        },
                        {
                          "id": $optionId2,
                          "name": "Option 2"
                        }
                      ]
                    },
                    {
                      "id": $tableVariableId,
                      "name": "A table",
                      "position": 7,
                      "type": "Table",
                      "isList": false,
                      "tableStyle": "Horizontal",
                      "columns": [
                        {
                          "isHeader": true,
                          "variable": {
                            "id": $columnId1,
                            "name": "Column 1",
                            "position": 8,
                            "type": "Date",
                            "isList": false
                          }
                        },
                        {
                          "isHeader": false,
                          "variable": {
                            "id": $columnId2,
                            "name": "Column 2",
                            "position": 9,
                            "type": "Link",
                            "isList": false
                          }
                        }
                      ]
                    },
                    {
                      "id": $textVariableId,
                      "name": "A paragraph",
                      "position": 10,
                      "type": "Text",
                      "isList": false,
                      "textType": "MultiLine"
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true)
    }
  }
}
