package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import jakarta.ws.rs.core.UriBuilder
import java.math.BigDecimal
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class VariablesControllerTest : ControllerIntegrationTest() {
  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
  }

  @Nested
  inner class ListVariables {
    private fun path(
        documentId: DocumentId? = inserted.documentId,
        stableIds: List<String>? = null,
        variableIds: List<VariableId>? = null,
    ): String {
      val builder = UriBuilder.fromPath("/api/v1/document-producer/variables")

      documentId?.let { builder.queryParam("documentId", it) }
      stableIds?.forEach { builder.queryParam("stableId", it) }
      variableIds?.forEach { builder.queryParam("variableId", it) }

      return builder.build().toString()
    }

    @Test
    fun `includes variables that are injected into sections`() {
      val manifestId1 = insertVariableManifest()
      val documentId = insertDocument()

      val variableIdOutdated =
          insertNumberVariable(
              id =
                  insertVariable(
                      name = "Number Variable",
                      stableId = "1",
                      type = VariableType.Number,
                  ),
              maxValue = BigDecimal(10),
          )

      val parentSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId1,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Parent section",
                              stableId = "100",
                              type = VariableType.Section,
                          )
                  ),
              position = 1,
          )
      val childSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId1,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Child section",
                              stableId = "101",
                              type = VariableType.Section,
                          ),
                      parentId = parentSectionVariableId,
                      renderHeading = false,
                  ),
              position = 2,
          )

      // The variable is injected into the section along with some text
      insertSectionValue(
          variableId = childSectionVariableId,
          listPosition = 0,
          textValue = "Section text",
      )
      insertSectionValue(
          variableId = childSectionVariableId,
          listPosition = 1,
          usedVariableId = variableIdOutdated,
      )

      // Variable is updated at some point
      insertNumberVariable(
          id =
              insertVariable(
                  name = "Number Variable",
                  replacesVariableId = variableIdOutdated,
                  stableId = "1",
                  type = VariableType.Number,
              ),
          maxValue = BigDecimal(5),
      )

      mockMvc
          .get(path(documentId))
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "type": "Section",
                      "children": [
                        {
                            "type": "Section",
                            "children": [],
                            "name": "Child section",
                            "id": $childSectionVariableId,
                            "stableId": "101",
                            "position": 2,
                            "internalOnly": false,
                            "isRequired": false,
                            "isList": true,
                            "renderHeading": false,
                            "recommends": []
                        }
                      ],
                      "name": "Parent section",
                      "id": $parentSectionVariableId,
                      "stableId": "100",
                      "position": 1,
                      "internalOnly": false,
                      "isRequired": false,
                      "isList": true,
                      "renderHeading": true,
                      "recommends": []
                    },
                    {
                        "type": "Number",
                        "name": "Number Variable",
                        "id": $variableIdOutdated,
                        "maxValue": 10,
                        "stableId": "1",
                        "position": 0,
                        "internalOnly": false,
                        "isRequired": false,
                        "isList": false
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `only lists variables for requested document`() {
      val manifestId1 = insertVariableManifest()
      val documentId = insertDocument()
      val manifestId2 = insertVariableManifest()
      insertDocument()

      val variableId1 =
          insertVariable(
              deliverableQuestion = "Date Question",
              name = "Date Variable",
              type = VariableType.Date,
          )
      val variableId2 =
          insertVariable(
              deliverableQuestion = "Link Question",
              name = "Link Variable",
              type = VariableType.Link,
          )

      insertVariableManifestEntry(manifestId = manifestId1, variableId = variableId1, position = 0)
      insertVariableManifestEntry(manifestId = manifestId2, variableId = variableId2, position = 0)

      mockMvc
          .get(path(documentId))
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "id": $variableId1,
                      "name": "Date Variable",
                      "deliverableQuestion": "Date Question",
                      "position": 0,
                      "stableId": "1",
                      "type": "Date",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `includes internal variables only for global role users`() {
      val manifestId = insertVariableManifest()
      val documentId = insertDocument()

      val internalVariableId =
          insertVariable(
              deliverableQuestion = "Date Question",
              internalOnly = true,
              name = "Date Variable",
              type = VariableType.Date,
          )
      val externalVariableId =
          insertVariable(
              deliverableQuestion = "Link Question",
              internalOnly = false,
              name = "Link Variable",
              type = VariableType.Link,
          )

      insertVariableManifestEntry(
          manifestId = manifestId,
          variableId = internalVariableId,
          position = 0,
      )
      insertVariableManifestEntry(
          manifestId = manifestId,
          variableId = externalVariableId,
          position = 1,
      )

      mockMvc
          .get(path(documentId))
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "id": $externalVariableId,
                      "name": "Link Variable",
                      "deliverableQuestion": "Link Question",
                      "position": 1,
                      "stableId": "2",
                      "type": "Link",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )

      insertUserGlobalRole(userId = user.userId, role = GlobalRole.ReadOnly)

      mockMvc
          .get(path(documentId))
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "id": $internalVariableId,
                      "name": "Date Variable",
                      "deliverableQuestion": "Date Question",
                      "position": 0,
                      "stableId": "1",
                      "type": "Date",
                      "internalOnly": true,
                      "isList": false,
                      "isRequired": false
                    },
                    {
                      "id": $externalVariableId,
                      "name": "Link Variable",
                      "deliverableQuestion": "Link Question",
                      "position": 1,
                      "stableId": "2",
                      "type": "Link",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `represents section recommendations reciprocally`() {
      val sectionVariableId =
          insertVariableManifestEntry(
              insertSectionVariable(
                  id = insertVariable(name = "Section Variable", type = VariableType.Section)
              )
          )
      val dateVariableId =
          insertVariableManifestEntry(
              insertVariable(name = "Date Variable", type = VariableType.Date)
          )

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
                      "stableId": "1",
                      "type": "Section",
                      "internalOnly": false,
                      "isList": true,
                      "isRequired": false
                    },
                    {
                      "id": $dateVariableId,
                      "name": "Date Variable",
                      "position": 1,
                      "recommendedBy": [$sectionVariableId],
                      "stableId": "2",
                      "type": "Date",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `uses correct payload for each variable type`() {
      val dateVariableId =
          insertVariableManifestEntry(
              insertVariable(
                  type = VariableType.Date,
                  isList = true,
                  isRequired = true,
                  name = "A date",
                  description = "A description",
              )
          )
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
                  maxValue = BigDecimal.TEN,
              )
          )
      val parentSectionVariableId =
          insertVariableManifestEntry(
              insertSectionVariable(
                  id = insertVariable(type = VariableType.Section, name = "Parent section")
              )
          )
      val childSectionVariableId =
          insertVariableManifestEntry(
              insertSectionVariable(
                  id = insertVariable(type = VariableType.Section, name = "Child section"),
                  parentId = parentSectionVariableId,
                  renderHeading = false,
              )
          )
      val selectVariableId =
          insertVariableManifestEntry(
              insertSelectVariable(
                  id = insertVariable(type = VariableType.Select, name = "A select"),
                  isMultiple = true,
              )
          )
      val optionId1 =
          insertSelectOption(
              selectVariableId,
              "Option 1",
              description = "Description 1",
              renderedText = "Rendered 1",
          )
      val optionId2 = insertSelectOption(selectVariableId, "Option 2")
      val tableVariableId =
          insertVariableManifestEntry(
              insertTableVariable(id = insertVariable(type = VariableType.Table, name = "A table"))
          )
      val columnId1 =
          insertTableColumn(
              tableVariableId,
              insertVariableManifestEntry(
                  insertVariable(name = "Column 1", type = VariableType.Date)
              ),
              isHeader = true,
          )
      val columnId2 =
          insertVariableManifestEntry(
              insertTableColumn(
                  tableVariableId,
                  insertVariable(name = "Column 2", type = VariableType.Link),
              )
          )
      val textVariableId =
          insertVariableManifestEntry(
              insertTextVariable(
                  id = insertVariable(type = VariableType.Text, name = "A paragraph"),
                  textType = VariableTextType.MultiLine,
              )
          )

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
                      "stableId": "1",
                      "type": "Date",
                      "internalOnly": false,
                      "isList": true,
                      "isRequired": true
                    },
                    {
                      "id": $imageVariableId,
                      "name": "An image",
                      "position": 1,
                      "stableId": "2",
                      "type": "Image",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false
                    },
                    {
                      "id": $linkVariableId,
                      "name": "A link",
                      "position": 2,
                      "stableId": "3",
                      "type": "Link",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false
                    },
                    {
                      "id": $numberVariableId,
                      "name": "A number",
                      "position": 3,
                      "stableId": "4",
                      "type": "Number",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
                      "decimalPlaces": 1,
                      "minValue": 2,
                      "maxValue": 10
                    },
                    {
                      "id": $parentSectionVariableId,
                      "name": "Parent section",
                      "position": 4,
                      "type": "Section",
                      "internalOnly": false,
                      "isList": true,
                      "isRequired": false,
                      "stableId": "5",
                      "renderHeading": true,
                      "recommends": [],
                      "children": [
                        {
                          "id": $childSectionVariableId,
                          "name": "Child section",
                          "position": 5,
                          "type": "Section",
                          "internalOnly": false,
                          "isList": true,
                          "isRequired": false,
                          "renderHeading": false,
                          "recommends": [],
                          "stableId": "6",
                          "children": []
                        }
                      ]
                    },
                    {
                      "id": $selectVariableId,
                      "name": "A select",
                      "position": 6,
                      "stableId": "7",
                      "type": "Select",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
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
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
                      "stableId": "8",
                      "tableStyle": "Horizontal",
                      "columns": [
                        {
                          "isHeader": true,
                          "variable": {
                            "id": $columnId1,
                            "name": "Column 1",
                            "position": 8,
                            "stableId": "9",
                            "type": "Date",
                            "internalOnly": false,
                            "isList": false,
                            "isRequired": false
                          }
                        },
                        {
                          "isHeader": false,
                          "variable": {
                            "id": $columnId2,
                            "name": "Column 2",
                            "position": 9,
                            "stableId": "10",
                            "type": "Link",
                            "internalOnly": false,
                            "isList": false,
                            "isRequired": false
                          }
                        }
                      ]
                    },
                    {
                      "id": $textVariableId,
                      "name": "A paragraph",
                      "position": 10,
                      "type": "Text",
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
                      "stableId": "11",
                      "textType": "MultiLine"
                    }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `returns definitions of specific variables if requested`() {
      val stableIdPrefix = "${UUID.randomUUID()}"

      insertModule()
      val deliverableId = insertDeliverable()
      val externalVariableId1 =
          insertTextVariable(deliverableId = deliverableId, stableId = "$stableIdPrefix-1")
      val externalVariableId2 = insertTextVariable(stableId = "$stableIdPrefix-2")

      // Internal-only variable shouldn't be returned because user doesn't have permission.
      val internalVariableId =
          insertTextVariable(
              insertVariable(
                  internalOnly = true,
                  stableId = "$stableIdPrefix-3",
                  type = VariableType.Text,
              )
          )

      // Additional variable in deliverable, but we won't request it.
      insertTextVariable(deliverableId = deliverableId, stableId = "$stableIdPrefix-4")

      mockMvc
          .get(
              path(
                  documentId = null,
                  variableIds =
                      listOf(
                          externalVariableId1,
                          externalVariableId2,
                          // Specify the same variable twice; should only be returned once.
                          externalVariableId2,
                          internalVariableId,
                      ),
              )
          )
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "deliverableId": $deliverableId,
                      "id": $externalVariableId1,
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
                      "name": "Variable 1",
                      "position": 0,
                      "stableId": "$stableIdPrefix-1",
                      "textType": "SingleLine",
                      "type": "Text"
                    },
                    {
                      "id": $externalVariableId2,
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
                      "name": "Variable 2",
                      "position": 0,
                      "stableId": "$stableIdPrefix-2",
                      "textType": "SingleLine",
                      "type": "Text"
                    }
                  ],
                  "status": "ok"
                }
              """
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `returns definitions of specific variables by stable ID if requested`() {
      val stableIdPrefix = "${UUID.randomUUID()}"

      insertModule()
      val deliverableId = insertDeliverable()
      val externalVariableId1 =
          insertTextVariable(deliverableId = deliverableId, stableId = "$stableIdPrefix-1")
      val externalVariableId2 = insertTextVariable(stableId = "$stableIdPrefix-2")

      // Internal-only variable shouldn't be returned because user doesn't have permission.
      insertTextVariable(
          insertVariable(
              internalOnly = true,
              stableId = "$stableIdPrefix-3",
              type = VariableType.Text,
          )
      )

      // Additional variable in deliverable, but we won't request it.
      insertTextVariable(deliverableId = deliverableId, stableId = "$stableIdPrefix-4")

      mockMvc
          .get(
              path(
                  documentId = null,
                  stableIds =
                      listOf(
                          "$stableIdPrefix-1",
                          "$stableIdPrefix-2",
                          // Specify the same variable twice; should only be returned once.
                          "$stableIdPrefix-2",
                          "$stableIdPrefix-3",
                      ),
              )
          )
          .andExpectJson(
              """
                {
                  "variables": [
                    {
                      "deliverableId": $deliverableId,
                      "id": $externalVariableId1,
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
                      "name": "Variable 1",
                      "position": 0,
                      "stableId": "$stableIdPrefix-1",
                      "textType": "SingleLine",
                      "type": "Text"
                    },
                    {
                      "id": $externalVariableId2,
                      "internalOnly": false,
                      "isList": false,
                      "isRequired": false,
                      "name": "Variable 2",
                      "position": 0,
                      "stableId": "$stableIdPrefix-2",
                      "textType": "SingleLine",
                      "type": "Text"
                    }
                  ],
                  "status": "ok"
                }
              """
                  .trimIndent(),
              strict = true,
          )
    }
  }
}
