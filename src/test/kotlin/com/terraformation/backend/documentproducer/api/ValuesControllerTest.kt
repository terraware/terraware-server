package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.USER_GLOBAL_ROLES
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.pojos.VariableImageValuesRow
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

class ValuesControllerTest : ControllerIntegrationTest() {
  @BeforeEach
  fun setUp() {
    insertUserGlobalRole(userId = currentUser().userId, GlobalRole.TFExpert)
    insertOrganization()
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
    insertProject()
  }

  private fun path(projectId: ProjectId = inserted.projectId) =
      "/api/v1/document-producer/projects/$projectId/values"

  @Nested
  inner class ListProjectVariableValues {
    @Test
    fun `returns appropriate data structures for all value types`() {
      val dateVariableId = insertVariable(type = VariableType.Date)
      val emailVariableId = insertVariable(type = VariableType.Email)
      val imageVariableId = insertVariable(type = VariableType.Image)
      val numberVariableId = insertNumberVariable()
      val textVariableId = insertTextVariable()
      val linkVariableId = insertVariable(type = VariableType.Link)
      val sectionVariableId = insertSectionVariable()
      val selectVariableId = insertSelectVariable()
      val selectOptionId1 = insertSelectOption(selectVariableId, "Option 1")
      val selectOptionId2 = insertSelectOption(selectVariableId, "Option 2")
      val tableVariableId = insertTableVariable()

      val dateValueId =
          insertValue(variableId = dateVariableId, dateValue = LocalDate.of(2023, 9, 25))
      val emailValueId = insertValue(variableId = emailVariableId, textValue = "email@example.com")
      val imageValueId = insertImageValue(imageVariableId, insertFile(), caption = "Image caption")
      val numberValueId =
          insertValue(variableId = numberVariableId, numberValue = BigDecimal(12345))
      val textValueId = insertValue(variableId = textVariableId, textValue = "Text value")
      val linkValueId =
          insertLinkValue(variableId = linkVariableId, url = "https://dummy", title = "Link title")
      val sectionTextValueId =
          insertSectionValue(
              variableId = sectionVariableId,
              listPosition = 0,
              textValue = "Section text",
          )
      val sectionVariableValueId =
          insertSectionValue(
              variableId = sectionVariableId,
              listPosition = 1,
              usedVariableId = dateVariableId,
          )
      val selectValueId =
          insertSelectValue(
              variableId = selectVariableId,
              optionIds = setOf(selectOptionId1, selectOptionId2),
          )
      val tableValueId = insertValue(citation = "Table citation", variableId = tableVariableId)

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": ${tableValueId.value + 1},
                  "values": [
                    {
                      "variableId": $dateVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $dateValueId,
                          "listPosition": 0,
                          "type": "Date",
                          "dateValue": "2023-09-25"
                        }
                      ]
                    },
                    {
                      "variableId": $emailVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $emailValueId,
                          "listPosition": 0,
                          "type": "Email",
                          "emailValue": "email@example.com"
                        }
                      ]
                    },
                    {
                      "variableId": $imageVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $imageValueId,
                          "listPosition": 0,
                          "type": "Image",
                          "caption": "Image caption"
                        }
                      ]
                    },
                    {
                      "variableId": $numberVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $numberValueId,
                          "listPosition": 0,
                          "type": "Number",
                          "numberValue": 12345
                        }
                      ]
                    },
                    {
                      "variableId": $textVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $textValueId,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Text value"
                        }
                      ]
                    },
                    {
                      "variableId": $linkVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $linkValueId,
                          "listPosition": 0,
                          "type": "Link",
                          "url": "https://dummy",
                          "title": "Link title"
                        }
                      ]
                    },
                    {
                      "variableId": $sectionVariableId,
                      "status": "Incomplete",
                      "values": [
                        {
                          "id": $sectionTextValueId,
                          "listPosition": 0,
                          "type": "SectionText",
                          "textValue": "Section text"
                        },
                        {
                          "id": $sectionVariableValueId,
                          "listPosition": 1,
                          "type": "SectionVariable",
                          "variableId": $dateVariableId,
                          "usageType": "Injection",
                          "displayStyle": "Block"
                        }
                      ]
                    },
                    {
                      "variableId": $selectVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $selectValueId,
                          "listPosition": 0,
                          "type": "Select",
                          "optionValues": [
                            $selectOptionId1,
                            $selectOptionId2
                          ]
                        }
                      ]
                    },
                    {
                      "variableId": $tableVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $tableValueId,
                          "listPosition": 0,
                          "type": "Table",
                          "citation": "Table citation"
                        }
                      ]
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
    fun `associates values with table rows`() {
      val tableVariableId = insertTableVariable()
      val textVariableId =
          insertTextVariable(insertVariable(isList = true, type = VariableType.Text))
      insertTableColumn(tableVariableId, textVariableId)

      val rowValueId0 =
          insertValue(variableId = tableVariableId, listPosition = 0, type = VariableType.Table)
      val rowValueId1 =
          insertValue(variableId = tableVariableId, listPosition = 1, type = VariableType.Table)

      val textValueId00 =
          insertValue(variableId = textVariableId, listPosition = 0, textValue = "Row 0 item 0")
      insertValueTableRow(textValueId00, rowValueId0)
      val textValueId01 =
          insertValue(variableId = textVariableId, listPosition = 1, textValue = "Row 0 item 1")
      insertValueTableRow(textValueId01, rowValueId0)
      val textValueId10 =
          insertValue(variableId = textVariableId, listPosition = 0, textValue = "Row 1 item 0")
      insertValueTableRow(textValueId10, rowValueId1)

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": ${textValueId10.value + 1},
                  "values": [
                    {
                      "variableId": $tableVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $rowValueId0,
                          "listPosition": 0,
                          "type": "Table"
                        },
                        {
                          "id": $rowValueId1,
                          "listPosition": 1,
                          "type": "Table"
                        }
                      ]
                    },
                    {
                      "variableId": $textVariableId,
                      "rowValueId": $rowValueId0,
                      "values": [
                        {
                          "id": $textValueId00,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Row 0 item 0"
                        },
                        {
                          "id": $textValueId01,
                          "listPosition": 1,
                          "type": "Text",
                          "textValue": "Row 0 item 1"
                        }
                      ]
                    },
                    {
                      "variableId": $textVariableId,
                      "rowValueId": $rowValueId1,
                      "values": [
                        {
                          "id": $textValueId10,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Row 1 item 0"
                        }
                      ]
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
    fun `returns most recent workflow details`() {
      val variableId = insertTextVariable(insertVariable(isList = true, type = VariableType.Text))
      val valueId = insertValue(variableId = variableId, textValue = "Value")

      insertVariableWorkflowHistory(feedback = "Yuck", status = VariableWorkflowStatus.Rejected)
      insertVariableWorkflowHistory(status = VariableWorkflowStatus.Approved)

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": ${valueId.value + 1},
                  "values": [
                    {
                      "variableId": $variableId,
                      "status": "Approved",
                      "values": [
                        {
                          "id": $valueId,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value"
                        }
                      ]
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
    fun `returns internal variable values if user has permission to read it`() {
      val variableId =
          insertTextVariable(
              insertVariable(internalOnly = true, isList = true, type = VariableType.Text)
          )
      val valueId = insertValue(variableId = variableId, textValue = "Value")

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": ${valueId.value + 1},
                  "values": [
                    {
                      "variableId": $variableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $valueId,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value"
                        }
                      ]
                    }
                  ],
                  "status": "ok"
                }
              """
                  .trimIndent(),
              strict = true,
          )

      dslContext.deleteFrom(USER_GLOBAL_ROLES).execute()
      insertOrganizationUser(user.userId, createdBy = user.userId)

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": ${valueId.value + 1},
                  "values": [],
                  "status": "ok"
                }
              """
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `returns internal comment if user has permission to read it`() {
      val variableId = insertTextVariable(insertVariable(isList = true, type = VariableType.Text))
      val valueId = insertValue(variableId = variableId, textValue = "Value")

      insertVariableWorkflowHistory(
          feedback = "Looks good",
          internalComment = "Actually, it's just okay",
          status = VariableWorkflowStatus.Approved,
      )

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": ${valueId.value + 1},
                  "values": [
                    {
                      "variableId": $variableId,
                      "feedback": "Looks good",
                      "internalComment": "Actually, it's just okay",
                      "status": "Approved",
                      "values": [
                        {
                          "id": $valueId,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value"
                        }
                      ]
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
    fun `returns empty list of variable values if variable has workflow details but no value`() {
      // Insert a dummy value so we have a valid max value ID
      val variableId = insertTextVariable(insertVariable(type = VariableType.Text))
      insertValue(variableId = variableId, textValue = "Dummy")

      // We'll query the values from a project that doesn't have the dummy value.
      insertProject()
      insertVariableWorkflowHistory(
          feedback = "You need to fill this out",
          status = VariableWorkflowStatus.Rejected,
      )

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": 1,
                  "values": [
                    {
                      "variableId": $variableId,
                      "feedback": "You need to fill this out",
                      "status": "Rejected",
                      "values": []
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
    fun `does not return internal comment if user has no permission to read it`() {
      val variableId = insertTextVariable(insertVariable(isList = true, type = VariableType.Text))
      val valueId = insertValue(variableId = variableId, textValue = "Value")

      insertVariableWorkflowHistory(
          feedback = "Looks good",
          internalComment = "Actually, it's just okay",
          status = VariableWorkflowStatus.Approved,
      )

      dslContext.deleteFrom(USER_GLOBAL_ROLES).execute()
      insertOrganizationUser()

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": ${valueId.value + 1},
                  "values": [
                    {
                      "variableId": $variableId,
                      "feedback": "Looks good",
                      "status": "Approved",
                      "values": [
                        {
                          "id": $valueId,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value"
                        }
                      ]
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
    fun `returns values across multiple deliverables if variable IDs are specified`() {
      insertModule()
      val deliverableId1 = insertDeliverable()
      val deliverable1VariableId1 = insertTextVariable(deliverableId = deliverableId1)
      val deliverable1ValueId1 =
          insertValue(variableId = deliverable1VariableId1, textValue = "Value 1")
      val deliverableId2 = insertDeliverable()
      val deliverable2VariableId1 = insertTextVariable(deliverableId = deliverableId2)
      val deliverable2ValueId1 =
          insertValue(variableId = deliverable2VariableId1, textValue = "Value 3")

      // Has a value, but we won't request it so it shouldn't be included in the response.
      val deliverable1VariableId2 = insertTextVariable(deliverableId = deliverableId1)
      val deliverable2ValueId2 =
          insertValue(variableId = deliverable1VariableId2, textValue = "Value 2")

      // Has no value; we'll request it but it shouldn't be included in the response.
      val deliverable2VariableId2 = insertTextVariable(deliverableId = deliverableId2)

      val queryString =
          listOf(
                  deliverable1VariableId1,
                  deliverable2VariableId1,
                  deliverable2VariableId2,
              )
              .joinToString(separator = "&") { "variableId=$it" }

      mockMvc
          .get("${path()}?$queryString")
          .andExpectJson(
              """
                {
                  "nextValueId": ${deliverable2ValueId2.value + 1},
                  "values": [
                    {
                      "variableId": $deliverable1VariableId1,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $deliverable1ValueId1,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value 1"
                        }
                      ]
                    },
                    {
                      "variableId": $deliverable2VariableId1,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $deliverable2ValueId1,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value 3"
                        }
                      ]
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
    fun `returns values across multiple deliverables if stable IDs are specified`() {
      val stableIdPrefix = "${UUID.randomUUID()}"

      insertModule()
      val deliverableId1 = insertDeliverable()
      val deliverable1VariableId1 =
          insertTextVariable(deliverableId = deliverableId1, stableId = "$stableIdPrefix-11")
      val deliverable1ValueId1 =
          insertValue(variableId = deliverable1VariableId1, textValue = "Value 1")
      val deliverableId2 = insertDeliverable()
      val deliverable2VariableId1 =
          insertTextVariable(deliverableId = deliverableId2, stableId = "$stableIdPrefix-21")
      val deliverable2ValueId1 =
          insertValue(variableId = deliverable2VariableId1, textValue = "Value 3")

      // Has a value, but we won't request it so it shouldn't be included in the response.
      val deliverable1VariableId2 =
          insertTextVariable(deliverableId = deliverableId1, stableId = "$stableIdPrefix-12")
      val deliverable2ValueId2 =
          insertValue(variableId = deliverable1VariableId2, textValue = "Value 2")

      // Has no value; we'll request it but it shouldn't be included in the response.
      insertTextVariable(deliverableId = deliverableId2, stableId = "$stableIdPrefix-22")

      val queryString =
          listOf(
                  "$stableIdPrefix-11",
                  "$stableIdPrefix-21",
                  "$stableIdPrefix-22",
                  "nonexistent-stable-id",
              )
              .joinToString(separator = "&") { "stableId=$it" }

      mockMvc
          .get("${path()}?$queryString")
          .andExpectJson(
              """
                {
                  "nextValueId": ${deliverable2ValueId2.value + 1},
                  "values": [
                    {
                      "variableId": $deliverable1VariableId1,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $deliverable1ValueId1,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value 1"
                        }
                      ]
                    },
                    {
                      "variableId": $deliverable2VariableId1,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $deliverable2ValueId1,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Value 3"
                        }
                      ]
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

  @Nested
  inner class AppendProjectValues {
    @Test
    fun `can append to column of existing row`() {
      val tableVariableId = insertTableVariable()
      val columnVariableId = insertTextVariable()
      insertTableColumn(tableVariableId, columnVariableId)

      val rowValueId = insertValue(variableId = tableVariableId)

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Append",
                  "variableId": $columnVariableId,
                  "rowValueId": $rowValueId,
                  "value": {
                    "type": "Text",
                    "textValue": "Column value"
                  }
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isOk() } }

      // Operation will have inserted a new value; it will be the one with the highest ID
      val newValueId = variableValuesDao.findAll().map { it.id!! }.maxBy { it.value }

      val valueTableRow = variableValueTableRowsDao.fetchByTableRowValueId(rowValueId).single()

      assertEquals(
          newValueId,
          valueTableRow.variableValueId,
          "New value should have been associated with row",
      )
    }

    @Test
    fun `validates new values against variable settings`() {
      val variableId = insertNumberVariable(maxValue = BigDecimal.TWO)

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Append",
                  "variableId": $variableId,
                  "value": {
                    "type": "Number",
                    "numberValue": 15
                  }
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isBadRequest() } }

      assertTableEmpty(VARIABLE_VALUES, "Should not have stored invalid value")
    }

    @Test
    fun `can append values of all non-image variable types`() {
      val sectionVariableId = insertSectionVariable()
      val tableVariableId = insertTableVariable()
      val textVariableId = insertTextVariable()
      val numberVariableId = insertNumberVariable()
      val linkVariableId = insertVariable(type = VariableType.Link)
      val dateVariableId = insertVariable(type = VariableType.Date)
      val emailVariableId = insertVariable(type = VariableType.Email)
      val selectVariableId = insertSelectVariable()
      val selectOptionId = insertSelectOption(selectVariableId, "Option")

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Append",
                  "variableId": $sectionVariableId,
                  "value": {
                    "type": "SectionText",
                    "citation": "Citation",
                    "textValue": "Section text value"
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $sectionVariableId,
                  "value": {
                    "type": "SectionVariable",
                    "citation": "Citation",
                    "variableId": $textVariableId,
                    "usageType": "Injection",
                    "displayStyle": "Inline"
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $tableVariableId,
                  "value": {
                    "type": "Table",
                    "citation": "Citation"
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $textVariableId,
                  "value": {
                    "type": "Text",
                    "citation": "Citation",
                    "textValue": "Text value"
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $numberVariableId,
                  "value": {
                    "type": "Number",
                    "citation": "Citation",
                    "numberValue": 123
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $linkVariableId,
                  "value": {
                    "type": "Link",
                    "citation": "Citation",
                    "url": "https://google.com/"
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $dateVariableId,
                  "value": {
                    "type": "Date",
                    "citation": "Citation",
                    "dateValue": "2023-01-01T11:22:33Z"
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $emailVariableId,
                  "value": {
                    "type": "Email",
                    "citation": "Citation",
                    "emailValue": "append@example.com"
                  }
                },
                {
                  "operation": "Append",
                  "variableId": $selectVariableId,
                  "value": {
                    "type": "Select",
                    "citation": "Citation",
                    "optionIds": [$selectOptionId]
                  }
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isOk() } }
    }
  }

  @Nested
  inner class UpdateProjectValues {
    @Test
    fun `can update caption and citation of existing image value`() {
      val imageVariableId = insertVariable(type = VariableType.Image)
      val fileId = insertFile()
      val existingValueId = insertImageValue(imageVariableId, fileId, caption = "Old caption")

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Update",
                  "valueId": $existingValueId,
                  "value": {
                    "type": "Image",
                    "caption": "New caption",
                    "citation": "New citation"
                  }
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isOk() } }

      val newValueId = variableValuesDao.findAll().map { it.id!! }.maxBy { it.value }

      val imageValuesRows = variableImageValuesDao.findAll().toSet()

      assertSetEquals(
          setOf(
              VariableImageValuesRow(
                  caption = "Old caption",
                  fileId = fileId,
                  variableId = imageVariableId,
                  variableTypeId = VariableType.Image,
                  variableValueId = existingValueId,
              ),
              VariableImageValuesRow(
                  caption = "New caption",
                  fileId = fileId,
                  variableId = imageVariableId,
                  variableTypeId = VariableType.Image,
                  variableValueId = newValueId,
              ),
          ),
          imageValuesRows,
          "Caption should have been added to new value",
      )

      val valuesRow = variableValuesDao.fetchOneById(newValueId)!!

      assertEquals("New citation", valuesRow.citation, "New value should have new citation")
    }

    @Test
    fun `returns not found error if user has no permission to read internal only variable`() {
      val variableId =
          insertTextVariable(
              insertVariable(internalOnly = true, isList = true, type = VariableType.Text)
          )
      insertValue(variableId = variableId, textValue = "Value")

      dslContext.deleteFrom(USER_GLOBAL_ROLES).execute()
      insertOrganizationUser(user.userId, createdBy = user.userId)

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Append",
                  "variableId": $variableId,
                  "value": {
                    "type": "Text",
                    "textValue": "New Value"
                  }
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns unauthorized error if user has no permission to update internal only variable`() {
      val variableId =
          insertTextVariable(
              insertVariable(internalOnly = true, isList = true, type = VariableType.Text)
          )
      insertValue(variableId = variableId, textValue = "Value")

      dslContext.deleteFrom(USER_GLOBAL_ROLES).execute()
      insertUserGlobalRole(user.userId, GlobalRole.ReadOnly)

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Append",
                  "variableId": $variableId,
                  "value": {
                    "type": "Text",
                    "textValue": "New Value"
                  }
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isForbidden() } }
    }
  }

  @Nested
  inner class DeleteProjectValues {
    @Test
    fun `renumbers remaining items if deleting from list`() {
      val variableId = insertTextVariable()
      val valueId0 = insertValue(variableId = variableId, listPosition = 0, textValue = "First")
      val valueId1 = insertValue(variableId = variableId, listPosition = 1, textValue = "Second")
      insertValue(variableId = variableId, listPosition = 2, textValue = "Third")
      insertValue(variableId = variableId, listPosition = 3, textValue = "Fourth")

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Delete",
                  "valueId": $valueId1
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isOk() } }

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "values": [
                    {
                      "variableId": $variableId,
                      "values": [
                        {
                          "id": $valueId0,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "First"
                        },
                        {
                          "listPosition": 1,
                          "type": "Text",
                          "textValue": "Third"
                        },
                        {
                          "listPosition": 2,
                          "type": "Text",
                          "textValue": "Fourth"
                        }
                      ]
                    }
                  ],
                  "status": "ok"
                }
              """
                  .trimIndent(),
          )
    }

    @Test
    fun `associates values with correctly-numbered rows after deleting earlier row`() {
      val tableVariableId = insertTableVariable()
      val columnVariableId = insertTextVariable()
      insertTableColumn(tableVariableId, columnVariableId)

      val rowId0 = insertValue(variableId = tableVariableId, listPosition = 0)
      val rowId1 = insertValue(variableId = tableVariableId, listPosition = 1)
      val rowId2 =
          insertValue(variableId = tableVariableId, listPosition = 2, citation = "Table citation")
      val valueId0 = insertValue(variableId = columnVariableId, textValue = "First")
      val valueId1 = insertValue(variableId = columnVariableId, textValue = "Second")
      val valueId2 = insertValue(variableId = columnVariableId, textValue = "Third")
      insertValueTableRow(valueId0, rowId0)
      insertValueTableRow(valueId1, rowId1)
      insertValueTableRow(valueId2, rowId2)

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Delete",
                  "valueId": $rowId0
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isOk() } }

      // Each of the remaining two rows gets a new value ID to hold its updated list position.
      val valueIds = variableValuesDao.findAll().map { it.id!!.value }.sorted()
      val valueId2Index = valueIds.indexOf(valueId2.value)
      val newIdForRow1 = valueIds[valueId2Index + 1]
      val newIdForRow2 = valueIds[valueId2Index + 2]

      // There'll be one new value for the deleted row, and one new value for the deleted cell
      // value, so the next value will be one more than that.
      val nextValueId = valueIds[valueId2Index + 4] + 1

      mockMvc
          .get(path())
          .andExpectJson(
              """
                {
                  "nextValueId": $nextValueId,
                  "values": [
                    {
                      "variableId": $tableVariableId,
                      "status": "Not Submitted",
                      "values": [
                        {
                          "id": $newIdForRow1,
                          "listPosition": 0,
                          "type": "Table"
                        },
                        {
                          "id": $newIdForRow2,
                          "listPosition": 1,
                          "type": "Table",
                          "citation": "Table citation"
                        }
                      ]
                    },
                    {
                      "variableId": $columnVariableId,
                      "rowValueId": $newIdForRow1,
                      "values": [
                        {
                          "id": $valueId1,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Second"
                        }
                      ]
                    },
                    {
                      "variableId": $columnVariableId,
                      "rowValueId": $newIdForRow2,
                      "values": [
                        {
                          "id": $valueId2,
                          "listPosition": 0,
                          "type": "Text",
                          "textValue": "Third"
                        }
                      ]
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
    fun `Returns not found error if user has no permission to read internal only variable`() {
      val variableId =
          insertTextVariable(
              insertVariable(internalOnly = true, isList = true, type = VariableType.Text)
          )
      val valueId = insertValue(variableId = variableId, textValue = "Value")

      dslContext.deleteFrom(USER_GLOBAL_ROLES).execute()
      insertOrganizationUser(user.userId, createdBy = user.userId)

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Delete",
                  "valueId": $valueId
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `Returns unauthorized error if user has no permission to update internal only variable`() {
      val variableId =
          insertTextVariable(
              insertVariable(internalOnly = true, isList = true, type = VariableType.Text)
          )
      val valueId = insertValue(variableId = variableId, textValue = "Value")

      dslContext.deleteFrom(USER_GLOBAL_ROLES).execute()
      insertUserGlobalRole(user.userId, GlobalRole.ReadOnly)

      val payload =
          """
            {
              "operations": [
                {
                  "operation": "Delete",
                  "valueId": $valueId
                }
              ]
            }
          """
              .trimIndent()

      mockMvc.post(path()) { content = payload }.andExpect { status { isForbidden() } }
    }
  }
}
