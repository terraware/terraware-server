package com.terraformation.pdd.variable.api

import com.terraformation.pdd.ControllerIntegrationTest
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.VariableTextType
import com.terraformation.pdd.jooq.VariableType
import java.math.BigDecimal
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class VariablesControllerTest : ControllerIntegrationTest() {
  @Nested
  inner class ListVariables {
    private fun path(manifestId: VariableManifestId = cannedVariableManifestId) =
        "/api/v1/variables?manifestId=$manifestId"

    @Test
    fun `only lists variables for requested manifest`() {
      val manifestId1 = insertVariableManifest()
      val manifestId2 = insertVariableManifest()
      val variableId1 = insertVariable(type = VariableType.Date)
      val variableId2 = insertVariable(type = VariableType.Link)

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
                      "name": "Variable $variableId1",
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
      val sectionVariableId = insertVariableManifestEntry(insertSectionVariable())
      val dateVariableId = insertVariableManifestEntry(insertVariable(type = VariableType.Date))

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
                      "name": "Variable $sectionVariableId",
                      "position": 0,
                      "recommends": [$dateVariableId],
                      "renderHeading": true,
                      "type": "Section",
                      "isList": true
                    },
                    {
                      "id": $dateVariableId,
                      "name": "Variable $dateVariableId",
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
              insertVariable(type = VariableType.Date, isList = true),
              name = "A date",
              description = "A description")
      val imageVariableId =
          insertVariableManifestEntry(insertVariable(type = VariableType.Image), name = "An image")
      val linkVariableId =
          insertVariableManifestEntry(insertVariable(type = VariableType.Link), name = "A link")
      val numberVariableId =
          insertVariableManifestEntry(
              insertNumberVariable(
                  decimalPlaces = 1, minValue = BigDecimal.TWO, maxValue = BigDecimal.TEN),
              name = "A number")
      val parentSectionVariableId =
          insertVariableManifestEntry(insertSectionVariable(), name = "Parent section")
      val childSectionVariableId =
          insertVariableManifestEntry(
              insertSectionVariable(parentId = parentSectionVariableId, renderHeading = false),
              name = "Child section")
      val selectVariableId =
          insertVariableManifestEntry(insertSelectVariable(isMultiple = true), name = "A select")
      val optionId1 =
          insertSelectOption(
              selectVariableId,
              "Option 1",
              description = "Description 1",
              renderedText = "Rendered 1")
      val optionId2 = insertSelectOption(selectVariableId, "Option 2")
      val tableVariableId = insertVariableManifestEntry(insertTableVariable(), name = "A table")
      val columnId1 =
          insertTableColumn(
              tableVariableId,
              insertVariableManifestEntry(
                  insertVariable(type = VariableType.Date), name = "Column 1"),
              isHeader = true)
      val columnId2 =
          insertVariableManifestEntry(
              insertTableColumn(tableVariableId, insertVariable(type = VariableType.Link)),
              name = "Column 2")
      val textVariableId =
          insertVariableManifestEntry(
              insertTextVariable(textType = VariableTextType.MultiLine), name = "A paragraph")

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
