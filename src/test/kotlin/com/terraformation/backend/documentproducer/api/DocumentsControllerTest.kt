package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.pojos.VariableValuesRow
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

class DocumentsControllerTest : ControllerIntegrationTest() {
  private val path = "/api/v1/document-producer/documents"

  @BeforeEach
  fun setUp() {
    insertUserGlobalRole(userId = user.userId, GlobalRole.TFExpert)
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    setupStableIdVariables()
  }

  @Nested
  inner class CreateDocument {
    @Test
    fun `fails when document template does not exist`() {
      val payload =
          """
          {
            "documentTemplateId": 12345,
            "name": "Test",
            "ownedBy": ${inserted.userId},
            "projectId": ${inserted.projectId}
          }"""
              .trimIndent()

      mockMvc.post(path) { content = payload }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `creates new document`() {
      val payload =
          """
          {
            "documentTemplateId": ${inserted.documentTemplateId},
            "name": "Test document",
            "ownedBy": ${inserted.userId},
            "projectId": ${inserted.projectId}
          }"""
              .trimIndent()

      mockMvc
          .post(path) { content = payload }
          .andExpectJson {
            val documentId = documentsDao.findAll().first().id!!

            """
                {
                  "document": {
                    "createdBy": ${inserted.userId},
                    "createdTime": "${Instant.EPOCH}",
                    "id": $documentId,
                    "modifiedBy": ${inserted.userId},
                    "modifiedTime": "${Instant.EPOCH}",
                    "name": "Test document",
                    "ownedBy": ${inserted.userId},
                    "projectId": ${inserted.projectId},
                    "status": "Draft"
                  }
                }
              """
          }
    }

    @Test
    fun `populates sections with default values`() {
      // Other values may exist for a project before a new document is created
      insertModule()
      val deliverableId = insertDeliverable()
      val variableId = insertVariable(deliverableId = deliverableId)
      insertTextVariable(variableId)
      insertValue(variableId = variableId, projectId = inserted.projectId, textValue = "Text")

      val textVariableId = insertVariableManifestEntry(insertTextVariable())
      val sectionId = insertVariableManifestEntry(insertSectionVariable(renderHeading = false))
      insertDefaultSectionValue(sectionId, listPosition = 0, textValue = "Some text")
      insertDefaultSectionValue(sectionId, listPosition = 1, usedVariableId = textVariableId)

      val payload =
          """
          {
            "documentTemplateId": ${inserted.documentTemplateId},
            "name": "Test document",
            "ownedBy": ${inserted.userId},
            "projectId": ${inserted.projectId}
          }"""
              .trimIndent()

      mockMvc.post(path) { content = payload }.andExpect { status { isOk() } }

      val values = variableSectionValuesDao.findAll().sortedBy { it.variableValueId }

      assertEquals(2, values.size, "Should have copied both default value entries")
      assertEquals("Some text", values[0].textValue, "Should have copied text value")
      assertEquals(
          textVariableId,
          values[1].usedVariableId,
          "Should have copied variable reference",
      )
    }
  }

  @Nested
  inner class ListDocuments {
    @Test
    fun `returns document details`() {
      val projectId = inserted.projectId
      val otherProjectId = insertProject(name = "Other Project")
      val otherDocumentTemplateId = insertDocumentTemplate()
      val otherVariableManifestId =
          insertVariableManifest(documentTemplateId = otherDocumentTemplateId)
      val otherUserId = insertUser()
      val otherCreatedTime = Instant.EPOCH.plusSeconds(100)
      val otherModifiedTime = otherCreatedTime.plusSeconds(1)
      val documentId1 = insertDocument(projectId = projectId)
      val documentId2 =
          insertDocument(
              createdBy = inserted.userId,
              createdTime = otherCreatedTime,
              documentTemplateId = otherDocumentTemplateId,
              modifiedBy = inserted.userId,
              modifiedTime = otherModifiedTime,
              ownedBy = otherUserId,
              projectId = otherProjectId,
              status = DocumentStatus.Locked,
              variableManifestId = otherVariableManifestId,
          )

      mockMvc
          .get(path)
          .andExpectJson(
              """
                {
                  "documents": [
                    {
                      "createdBy": ${inserted.userId},
                      "createdTime": "${Instant.EPOCH}",
                      "id": $documentId1,
                      "documentTemplateId": ${inserted.documentTemplateId},
                      "modifiedBy": ${inserted.userId},
                      "modifiedTime": "${Instant.EPOCH}",
                      "ownedBy": ${inserted.userId},
                      "projectId": $projectId,
                      "status": "Draft",
                      "variableManifestId": ${inserted.variableManifestId}
                    },
                    {
                      "createdBy": ${inserted.userId},
                      "createdTime": "$otherCreatedTime",
                      "id": $documentId2,
                      "documentTemplateId": $otherDocumentTemplateId,
                      "modifiedBy": ${inserted.userId},
                      "modifiedTime": "$otherModifiedTime",
                      "ownedBy": $otherUserId,
                      "projectId": $otherProjectId,
                      "status": "Locked",
                      "variableManifestId": $otherVariableManifestId
                    }
                  ]
                }"""
                  .trimIndent()
          )
    }

    @Test
    fun `can limit results to a single project`() {
      val projectId = inserted.projectId
      val documentId = insertDocument()
      insertProject(name = "Other Project")
      insertDocument()

      mockMvc
          .get("$path?projectId=$projectId")
          .andExpectJson(
              """
                {
                  "documents": [
                    {
                      "createdBy": ${inserted.userId},
                      "createdTime": "${Instant.EPOCH}",
                      "id": $documentId,
                      "documentTemplateId": ${inserted.documentTemplateId},
                      "modifiedBy": ${inserted.userId},
                      "modifiedTime": "${Instant.EPOCH}",
                      "ownedBy": ${inserted.userId},
                      "projectId": $projectId,
                      "status": "Draft",
                      "variableManifestId": ${inserted.variableManifestId}
                    }
                  ]
                }"""
                  .trimIndent()
          )
    }
  }

  @Nested
  inner class GetDocument {
    @Test
    fun `returns not found error for nonexistent document`() {
      mockMvc.get("$path/1").andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns a single document by id`() {
      insertDocument()
      mockMvc
          .get("$path/${inserted.documentId}")
          .andExpectJson(
              """
                {
                  "document": {
                    "createdBy": ${inserted.userId},
                    "createdTime": "${Instant.EPOCH}",
                    "id": ${inserted.documentId},
                    "documentTemplateId": ${inserted.documentTemplateId},
                    "modifiedBy": ${inserted.userId},
                    "modifiedTime": "${Instant.EPOCH}",
                    "ownedBy": ${inserted.userId},
                    "projectId": ${inserted.projectId},
                    "status": "Draft",
                    "variableManifestId": ${inserted.variableManifestId}
                  }
                }"""
                  .trimIndent()
          )
    }
  }

  @Nested
  inner class UpdateDocument {
    @Test
    fun `returns not found error for nonexistent document`() {
      val payload =
          """
          {
            "name": "Test Test document",
            "ownedBy": ${inserted.userId},
            "status": "Draft"
          }"""
              .trimIndent()

      mockMvc.put("$path/1") { content = payload }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `updates a document by id`() {
      val otherDocumentTemplateId = insertDocumentTemplate()
      val otherVariableManifestId =
          insertVariableManifest(documentTemplateId = otherDocumentTemplateId)
      val otherUserId = insertUser()
      val otherCreatedTime = Instant.EPOCH.plusSeconds(100)
      val otherModifiedTime = otherCreatedTime.plusSeconds(1)
      val documentId =
          insertDocument(
              createdBy = inserted.userId,
              createdTime = otherCreatedTime,
              documentTemplateId = otherDocumentTemplateId,
              modifiedBy = inserted.userId,
              modifiedTime = otherModifiedTime,
              ownedBy = otherUserId,
              status = DocumentStatus.Locked,
              variableManifestId = otherVariableManifestId,
          )

      val payload =
          """
          {
            "name": "Test Test document",
            "ownedBy": ${inserted.userId},
            "status": "Submitted"
          }"""
              .trimIndent()

      mockMvc.put("$path/$documentId") { content = payload }.andExpect { status { isOk() } }

      val documentsRow = documentsDao.fetchOneById(documentId)
      assertEquals("Test Test document", documentsRow!!.name, "Name")
      assertEquals(inserted.userId, documentsRow.ownedBy, "Owned by")
      assertEquals(DocumentStatus.Submitted, documentsRow.statusId, "Status")
    }
  }

  @Nested
  inner class CreateSavedVersion {
    private fun versionsPath(documentId: Any = inserted.documentId) = "$path/$documentId/versions"

    @Test
    fun `saves current maximum value ID and manifest ID`() {
      val payload = """{ "name": "Test" }"""

      val projectId = inserted.projectId
      val documentId = insertDocument()
      val otherProjectId = insertProject()
      val variableId = insertVariableManifestEntry(insertTextVariable())

      insertValue(projectId = projectId, variableId = variableId, textValue = "Value 1")
      val latestValueId =
          insertValue(projectId = projectId, variableId = variableId, textValue = "Value 2")
      insertValue(projectId = otherProjectId, variableId = variableId, textValue = "Other document")

      mockMvc
          .post(versionsPath(documentId)) { content = payload }
          .andExpectJson(
              """
                {
                  "version": {
                    "createdBy":${inserted.userId},
                    "createdTime": "${Instant.EPOCH}",
                    "isSubmitted": false,
                    "maxVariableValueId": $latestValueId,
                    "name": "Test",
                    "variableManifestId": ${inserted.variableManifestId}
                  },
                  "status": "ok"
                }"""
                  .trimIndent()
          )
    }

    @Test
    fun `returns not found error for nonexistent document`() {
      val payload = """{ "name": "Test" }"""

      mockMvc
          .post(versionsPath(DocumentId(300))) { content = payload }
          .andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns conflict error for document with no values`() {
      val documentId = insertDocument()
      val payload = """{ "name": "Test" }"""
      mockMvc
          .post(versionsPath(documentId)) { content = payload }
          .andExpect { status { isConflict() } }
    }
  }

  @Nested
  inner class GetSavedVersion {
    private fun versionsPath(versionId: Any, documentId: Any = inserted.documentId) =
        "/api/v1/document-producer/documents/$documentId/versions/$versionId"

    @Test
    fun `returns saved version details`() {
      insertDocument()

      val variableId = insertVariableManifestEntry(insertTextVariable())
      val valueId = insertValue(variableId = variableId, textValue = "Text")
      val versionId = insertSavedVersion(valueId, isSubmitted = true, name = "Test Version")

      mockMvc
          .get(versionsPath(versionId))
          .andExpectJson(
              """
                {
                  "version": {
                    "createdBy": ${inserted.userId},
                    "createdTime": "${Instant.EPOCH}",
                    "isSubmitted": true,
                    "maxVariableValueId": $valueId,
                    "name": "Test Version",
                    "variableManifestId": ${inserted.variableManifestId},
                    "versionId": $versionId
                  },
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true,
          )
    }

    @Test
    fun `returns not found error for nonexistent version`() {
      mockMvc.get(versionsPath(1, 1)).andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns not found error if version is from a different document`() {
      val documentId = insertDocument()
      val otherProjectId = insertProject()
      val otherDocumentId = insertDocument()
      val variableId = insertVariableManifestEntry(insertTextVariable())
      val valueId =
          insertValue(variableId = variableId, projectId = otherProjectId, textValue = "Text")
      val versionId = insertSavedVersion(valueId, otherDocumentId)

      mockMvc.get(versionsPath(versionId, documentId)).andExpect { status { isNotFound() } }
    }
  }

  @Nested
  inner class UpdateSavedVersion {
    private fun versionsPath(versionId: Any, documentId: Any = inserted.documentId) =
        "/api/v1/document-producer/documents/$documentId/versions/$versionId"

    @Test
    fun `updates saved version details`() {
      val payload = """{ "isSubmitted": true }"""

      insertDocument()
      val variableId = insertVariableManifestEntry(insertTextVariable())
      val valueId = insertValue(variableId = variableId, textValue = "Text")
      val versionId = insertSavedVersion(valueId, isSubmitted = false, name = "Test Version")

      mockMvc.put(versionsPath(versionId)) { content = payload }.andExpect { status { isOk() } }

      val versionsRow = documentSavedVersionsDao.fetchOneById(versionId)!!

      assertTrue(versionsRow.isSubmitted!!, "Should have updated submitted flag")
    }

    @Test
    fun `returns not found error for nonexistent version`() {
      val payload = """{ "isSubmitted": true }"""

      val documentId = insertDocument()
      mockMvc
          .put(versionsPath(1, documentId)) { content = payload }
          .andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns not found error if version is from a different document`() {
      val payload = """{ "isSubmitted": true }"""

      val documentId = insertDocument()
      val otherProjectId = insertProject()
      val otherDocumentId = insertDocument()
      val variableId = insertVariableManifestEntry(insertTextVariable())
      val valueId =
          insertValue(variableId = variableId, projectId = otherProjectId, textValue = "Text")
      val versionId = insertSavedVersion(valueId, otherDocumentId)

      mockMvc
          .put(versionsPath(versionId, documentId)) { content = payload }
          .andExpect { status { isNotFound() } }
    }
  }

  @Nested
  inner class GetDocumentHistory {
    private fun historyPath(documentId: Any = inserted.documentId) =
        "/api/v1/document-producer/documents/$documentId/history"

    @Test
    fun `returns correct sequence of edits and saved versions`() {
      var timestamp = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
      lateinit var lastValueId: VariableValueId

      val userId1 = insertUser()
      val userId2 = insertUser()
      val documentId = insertDocument(createdBy = userId1)
      val variableManifestId1 = insertVariableManifest()
      val variableId = insertVariableManifestEntry(insertTextVariable())

      fun insertNextValue(duration: Duration, userId: UserId): Instant {
        timestamp = timestamp.plus(duration)
        val instant = timestamp.toInstant()
        lastValueId =
            insertValue(
                projectId = inserted.projectId,
                variableId = variableId,
                createdBy = userId,
                createdTime = instant,
            )
        return instant
      }

      // 1970-01-01: Document created by user 1

      // 2023-01-01: two edits by user 1, one edit by user 2, saved by user 2, one edit by user 2.
      // Should collapse the two edits by user 1 and have two entries for user 2, one before and
      // one after the saved version.

      insertNextValue(Duration.ZERO, userId1)
      val day1User1Timestamp = insertNextValue(Duration.ofMinutes(1), userId1)
      val day1User2Timestamp1 = insertNextValue(Duration.ofMinutes(1), userId2)
      timestamp = timestamp.plusSeconds(1)
      val day1SavedVersionTimestamp = timestamp.toInstant()
      val day1MaxValueId = lastValueId
      val day1SavedVersionId =
          insertSavedVersion(
              day1MaxValueId,
              name = "Saved 1",
              createdBy = userId1,
              createdTime = day1SavedVersionTimestamp,
          )
      val day1User2Timestamp2 = insertNextValue(Duration.ofMinutes(1), userId2)

      // 2023-01-02: two edits by user 2. Should collapse to one history entry.
      insertNextValue(Duration.ofDays(1), userId2)
      val day2User2Timestamp = insertNextValue(Duration.ofSeconds(1), userId2)

      // 2023-01-03: two saved versions with no edits, but the second uses a different manifest ID.
      timestamp = timestamp.plusDays(1)
      val day3SavedVersionTimestamp1 = timestamp.toInstant()
      val day3MaxValueId = lastValueId
      val day3SavedVersionId1 =
          insertSavedVersion(
              day3MaxValueId,
              name = "Saved 2",
              createdBy = userId1,
              createdTime = day3SavedVersionTimestamp1,
          )

      val variableManifestId2 = insertVariableManifest()

      timestamp = timestamp.plusSeconds(1)
      val day3SavedVersionTimestamp2 = timestamp.toInstant()
      val day3SavedVersionId2 =
          insertSavedVersion(
              day3MaxValueId,
              name = "Saved 3",
              createdBy = userId1,
              createdTime = day3SavedVersionTimestamp2,
              isSubmitted = true,
              variableManifestId = variableManifestId2,
          )

      // 2023-01-04: two edits by user 1, two edits by user 2, two edits by user 1. Should collapse
      // to two history entries, one for each user.
      insertNextValue(Duration.ofDays(1), userId1)
      insertNextValue(Duration.ofSeconds(1), userId1)
      insertNextValue(Duration.ofSeconds(1), userId2)
      val day4User2Timestamp = insertNextValue(Duration.ofSeconds(1), userId2)
      insertNextValue(Duration.ofSeconds(1), userId1)
      val day4User1Timestamp = insertNextValue(Duration.ofSeconds(1), userId1)

      mockMvc
          .get(historyPath(documentId))
          .andExpectJson(
              """
                {
                  "history": [
                     {
                       "type": "Edited",
                       "createdBy": $userId1,
                       "createdTime": "$day4User1Timestamp"
                     },
                     {
                       "type": "Edited",
                       "createdBy": $userId2,
                       "createdTime": "$day4User2Timestamp"
                     },
                     {
                       "type": "Saved",
                       "createdBy": $userId1,
                       "createdTime": "$day3SavedVersionTimestamp2",
                       "isSubmitted": true,
                       "maxVariableValueId": $day3MaxValueId,
                       "name": "Saved 3",
                       "variableManifestId": $variableManifestId2,
                       "versionId": $day3SavedVersionId2
                     },
                     {
                       "type": "Saved",
                       "createdBy": $userId1,
                       "createdTime": "$day3SavedVersionTimestamp1",
                       "isSubmitted": false,
                       "maxVariableValueId": $day3MaxValueId,
                       "name": "Saved 2",
                       "variableManifestId": $variableManifestId1,
                       "versionId": $day3SavedVersionId1
                     },
                     {
                       "type": "Edited",
                       "createdBy": $userId2,
                       "createdTime": "$day2User2Timestamp"
                     },
                     {
                       "type": "Edited",
                       "createdBy": $userId2,
                       "createdTime": "$day1User2Timestamp2"
                     },
                     {
                       "type": "Saved",
                       "createdBy": $userId1,
                       "createdTime": "$day1SavedVersionTimestamp",
                       "isSubmitted": false,
                       "maxVariableValueId": $day1MaxValueId,
                       "name": "Saved 1",
                       "variableManifestId": $variableManifestId1,
                       "versionId": $day1SavedVersionId
                     },
                     {
                       "type": "Edited",
                       "createdBy": $userId2,
                       "createdTime": "$day1User2Timestamp1"
                     },
                     {
                       "type": "Edited",
                       "createdBy": $userId1,
                       "createdTime": "$day1User1Timestamp"
                     },
                     {
                       "type": "Created",
                       "createdBy": ${userId1},
                       "createdTime": "${Instant.EPOCH}"
                     }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent()
          )
    }

    @Test
    fun `returns not found error for nonexistent document`() {
      mockMvc.get(historyPath(1)).andExpect { status { isNotFound() } }
    }
  }

  @Nested
  inner class UpgradeManifest {
    private fun path(documentId: Any = inserted.documentId) =
        "/api/v1/document-producer/documents/$documentId/upgrade"

    private fun payload(manifestId: Any) = """{ "variableManifestId": $manifestId }"""

    @Test
    fun `upgrades empty document`() {
      insertDocument()
      insertVariableManifestEntry(insertTextVariable())
      val newManifestId = insertVariableManifest()
      insertVariableManifestEntry(insertTextVariable(), manifestId = newManifestId)

      mockMvc.post(path()) { content = payload(newManifestId) }.andExpect { status { isOk() } }

      assertEquals(
          newManifestId,
          documentsDao.fetchOneById(inserted.documentId)?.variableManifestId,
          "Manifest ID",
      )
    }

    // This just tests that the controller calls the code that inserts the needed values; the
    // logic for calculating the needed values is tested in DocumentUpgradeCalculatorTest.
    @Test
    fun `inserts new values for replacement variables`() {
      insertDocument()
      val oldVariableId = insertVariableManifestEntry(insertNumberVariable())
      val newManifestId = insertVariableManifest()
      val newVariableId =
          insertVariableManifestEntry(
              insertTextVariable(
                  insertVariable(type = VariableType.Text, replacesVariableId = oldVariableId)
              ),
              manifestId = newManifestId,
          )
      insertValue(variableId = oldVariableId, numberValue = BigDecimal.ONE, citation = "citation")

      mockMvc.post(path()) { content = payload(newManifestId) }.andExpect { status { isOk() } }

      assertEquals(
          newManifestId,
          documentsDao.fetchOneById(inserted.documentId)?.variableManifestId,
          "Manifest ID",
      )

      assertEquals(
          listOf(
              VariableValuesRow(
                  citation = "citation",
                  createdBy = inserted.userId,
                  createdTime = Instant.EPOCH,
                  isDeleted = false,
                  listPosition = 0,
                  projectId = inserted.projectId,
                  textValue = "1",
                  variableId = newVariableId,
                  variableTypeId = VariableType.Text,
              )
          ),
          variableValuesDao.fetchByVariableId(newVariableId).map { it.copy(id = null) },
          "Should have inserted new value",
      )
    }

    @Test
    fun `returns not found error for nonexistent document`() {
      mockMvc
          .post(path(123)) { content = payload(insertVariableManifest()) }
          .andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns not found error for nonexistent manifest`() {
      val documentId = insertDocument()

      mockMvc
          .post(path(documentId)) { content = payload(inserted.variableManifestId.value + 1) }
          .andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns conflict error if manifest is for different document template`() {
      insertDocument()
      insertVariableManifestEntry(insertTextVariable())
      val otherDocumentTemplateId = insertDocumentTemplate()
      val otherDocumentTemplateManifestId =
          insertVariableManifest(documentTemplateId = otherDocumentTemplateId)
      insertVariableManifestEntry(
          insertTextVariable(),
          manifestId = otherDocumentTemplateManifestId,
      )

      mockMvc
          .post(path()) { content = payload(otherDocumentTemplateManifestId) }
          .andExpect { status { isConflict() } }
    }

    @Test
    fun `returns bad request error if attempting to downgrade manifest`() {
      val manifestId1 = insertVariableManifest()
      insertVariableManifestEntry(insertTextVariable(), manifestId = manifestId1)
      val manifestId2 = insertVariableManifest()
      insertVariableManifestEntry(insertTextVariable(), manifestId = manifestId2)
      val documentId = insertDocument(variableManifestId = manifestId2)

      mockMvc
          .post(path(documentId)) { content = payload(manifestId1) }
          .andExpect { status { isBadRequest() } }
    }
  }
}
