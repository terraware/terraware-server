package com.terraformation.pdd.document.api

import com.terraformation.pdd.ControllerIntegrationTest
import com.terraformation.pdd.jooq.DocumentStatus
import com.terraformation.pdd.jooq.UserId
import com.terraformation.pdd.jooq.VariableType
import com.terraformation.pdd.jooq.VariableValueId
import com.terraformation.pdd.jooq.tables.pojos.VariableValuesRow
import com.terraformation.pdd.jooq.tables.references.DOCUMENTS
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

class DocumentsControllerTest : ControllerIntegrationTest() {
  override val tablesToResetSequences = listOf(DOCUMENTS)

  private val path = "/api/v1/pdds"

  @Nested
  inner class CreateDocument {
    @Test
    fun `fails when methodology does not exist`() {
      val payload =
          """
            {
              "methodologyId": 12345,
              "name": "Test",
              "organizationName": "Test",
              "ownedBy": $cannedInternalUserId
            }"""
              .trimIndent()

      mockMvc.post(path) { content = payload }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `creates new document`() {
      val payload =
          """
            {
              "methodologyId": 1,
              "name": "Test document",
              "organizationName": "Org Name",
              "ownedBy": $cannedInternalUserId
            }"""
              .trimIndent()

      mockMvc
          .post(path) { content = payload }
          .andExpectJson(
              """
                {
                  "pdd": {
                    "createdBy": ${requestUser?.id},
                    "createdTime": "${Instant.EPOCH}",
                    "id": 1,
                    "modifiedBy": ${requestUser?.id},
                    "modifiedTime": "${Instant.EPOCH}",
                    "name": "Test document",
                    "organizationName": "Org Name",
                    "ownedBy": $cannedInternalUserId,
                    "status": "Draft"
                  }
                }
              """
                  .trimIndent(),
          )
    }
  }

  @Nested
  inner class ListDocuments {
    @Test
    fun `returns document details`() {
      val otherMethodologyId = insertMethodology()
      val otherVariableManifestId = insertVariableManifest(methodologyId = otherMethodologyId)
      val otherUserId = insertUser()
      val otherCreatedTime = Instant.EPOCH.plusSeconds(100)
      val otherModifiedTime = otherCreatedTime.plusSeconds(1)
      val documentId1 = insertDocument()
      val documentId2 =
          insertDocument(
              createdBy = cannedAdminId,
              createdTime = otherCreatedTime,
              methodologyId = otherMethodologyId,
              modifiedBy = cannedInternalUserId,
              modifiedTime = otherModifiedTime,
              organizationName = "Other Org",
              ownedBy = otherUserId,
              status = DocumentStatus.Locked,
              variableManifestId = otherVariableManifestId,
          )

      mockMvc
          .get(path)
          .andExpectJson(
              """
                {
                  "pdds": [
                    {
                      "createdBy": $cannedInternalUserId,
                      "createdTime": "${Instant.EPOCH}",
                      "id": $documentId1,
                      "methodologyId": $cannedMethodologyId,
                      "modifiedBy": $cannedInternalUserId,
                      "modifiedTime": "${Instant.EPOCH}",
                      "organizationName": "Test Org",
                      "ownedBy": $cannedInternalUserId,
                      "status": "Draft",
                      "variableManifestId": $cannedVariableManifestId
                    },
                    {
                      "createdBy": $cannedAdminId,
                      "createdTime": "$otherCreatedTime",
                      "id": $documentId2,
                      "methodologyId": $otherMethodologyId,
                      "modifiedBy": $cannedInternalUserId,
                      "modifiedTime": "$otherModifiedTime",
                      "organizationName": "Other Org",
                      "ownedBy": $otherUserId,
                      "status": "Locked",
                      "variableManifestId": $otherVariableManifestId
                    }
                  ]
                }"""
                  .trimIndent())
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
      val documentId = insertDocument()
      mockMvc
          .get("$path/$documentId")
          .andExpectJson(
              """
                {
                  "pdd": {
                    "createdBy": $cannedInternalUserId,
                    "createdTime": "${Instant.EPOCH}",
                    "id": $documentId,
                    "methodologyId": $cannedMethodologyId,
                    "modifiedBy": $cannedInternalUserId,
                    "modifiedTime": "${Instant.EPOCH}",
                    "organizationName": "Test Org",
                    "ownedBy": $cannedInternalUserId,
                    "status": "Draft",
                    "variableManifestId": $cannedVariableManifestId
                  }
                }"""
                  .trimIndent())
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
              "organizationName": "New New Org",
              "ownedBy": $cannedInternalUserId
            }"""
              .trimIndent()

      mockMvc.put("$path/1") { content = payload }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `updates a document by id`() {
      val otherMethodologyId = insertMethodology()
      val otherVariableManifestId = insertVariableManifest(methodologyId = otherMethodologyId)
      val otherUserId = insertUser()
      val otherCreatedTime = Instant.EPOCH.plusSeconds(100)
      val otherModifiedTime = otherCreatedTime.plusSeconds(1)
      val documentId =
          insertDocument(
              createdBy = cannedAdminId,
              createdTime = otherCreatedTime,
              methodologyId = otherMethodologyId,
              modifiedBy = cannedInternalUserId,
              modifiedTime = otherModifiedTime,
              organizationName = "Other Org",
              ownedBy = otherUserId,
              status = DocumentStatus.Locked,
              variableManifestId = otherVariableManifestId,
          )

      val payload =
          """
            {
              "name": "Test Test document",
              "organizationName": "New New Org",
              "ownedBy": $cannedInternalUserId
            }"""
              .trimIndent()

      mockMvc.put("$path/$documentId") { content = payload }.andExpect { status { isOk() } }

      val documentsRow = documentsDao.fetchOneById(documentId)
      assertEquals(documentsRow!!.name, "Test Test document")
      assertEquals(documentsRow.organizationName, "New New Org")
      assertEquals(documentsRow.ownedBy, cannedInternalUserId)
    }
  }

  @Nested
  inner class CreateSavedVersion {
    private fun versionsPath(documentId: Any = cannedDocumentId) = "$path/$documentId/versions"

    @Test
    fun `saves current maximum value ID and manifest ID`() {
      val payload = """{ "name": "Test" }"""

      val otherdocumentId = insertDocument()
      val variableId = insertVariableManifestEntry(insertTextVariable())

      insertValue(variableId = variableId, textValue = "Value 1")
      val latestValueId = insertValue(variableId = variableId, textValue = "Value 2")
      insertValue(
          variableId = variableId, documentId = otherdocumentId, textValue = "Other document")

      mockMvc
          .post(versionsPath()) { content = payload }
          .andExpectJson(
              """
                {
                  "version": {
                    "createdBy":${requestUser?.id},
                    "createdTime": "${Instant.EPOCH}",
                    "isSubmitted": false,
                    "maxVariableValueId": $latestValueId,
                    "name": "Test",
                    "variableManifestId": $cannedVariableManifestId
                  },
                  "status": "ok"
                }"""
                  .trimIndent())
    }

    @Test
    fun `returns not found error for nonexistent document`() {
      val payload = """{ "name": "Test" }"""

      mockMvc.post(versionsPath(1)) { content = payload }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns conflict error for document with no values`() {
      val payload = """{ "name": "Test" }"""

      mockMvc.post(versionsPath()) { content = payload }.andExpect { status { isConflict() } }
    }
  }

  @Nested
  inner class GetSavedVersion {
    private fun versionsPath(versionId: Any, documentId: Any = cannedDocumentId) =
        "/api/v1/pdds/$documentId/versions/$versionId"

    @Test
    fun `returns saved version details`() {
      val variableId = insertVariableManifestEntry(insertTextVariable())
      val valueId = insertValue(variableId = variableId, textValue = "Text")
      val versionId = insertSavedVersion(valueId, isSubmitted = true, name = "Test Version")

      mockMvc
          .get(versionsPath(versionId))
          .andExpectJson(
              """
                {
                  "version": {
                    "createdBy": $cannedInternalUserId,
                    "createdTime": "${Instant.EPOCH}",
                    "isSubmitted": true,
                    "maxVariableValueId": $valueId,
                    "name": "Test Version",
                    "variableManifestId": $cannedVariableManifestId,
                    "versionId": $versionId
                  },
                  "status": "ok"
                }"""
                  .trimIndent(),
              strict = true)
    }

    @Test
    fun `returns not found error for nonexistent version`() {
      mockMvc.get(versionsPath(1)).andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns not found error if version is from a different document`() {
      val otherdocumentId = insertDocument()
      val variableId = insertVariableManifestEntry(insertTextVariable())
      val valueId =
          insertValue(variableId = variableId, documentId = otherdocumentId, textValue = "Text")
      val versionId = insertSavedVersion(valueId, otherdocumentId)

      mockMvc.get(versionsPath(versionId, cannedDocumentId)).andExpect { status { isNotFound() } }
    }
  }

  @Nested
  inner class UpdateSavedVersion {
    private fun versionsPath(versionId: Any, documentId: Any = cannedDocumentId) =
        "/api/v1/pdds/$documentId/versions/$versionId"

    @Test
    fun `updates saved version details`() {
      val payload = """{ "isSubmitted": true }"""

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

      mockMvc.put(versionsPath(1)) { content = payload }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns not found error if version is from a different document`() {
      val payload = """{ "isSubmitted": true }"""

      val otherDocumentId = insertDocument()
      val variableId = insertVariableManifestEntry(insertTextVariable())
      val valueId =
          insertValue(variableId = variableId, documentId = otherDocumentId, textValue = "Text")
      val versionId = insertSavedVersion(valueId, otherDocumentId)

      mockMvc
          .put(versionsPath(versionId, cannedDocumentId)) { content = payload }
          .andExpect { status { isNotFound() } }
    }
  }

  @Nested
  inner class GetDocumentHistory {
    private fun historyPath(documentId: Any = cannedDocumentId) = "/api/v1/pdds/$documentId/history"

    @Test
    fun `returns correct sequence of edits and saved versions`() {
      var timestamp = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
      lateinit var lastValueId: VariableValueId

      val userId1 = insertUser()
      val userId2 = insertUser()
      val variableId = insertVariableManifestEntry(insertTextVariable())

      fun insertNextValue(duration: Duration, userId: UserId): Instant {
        timestamp = timestamp.plus(duration)
        val instant = timestamp.toInstant()
        lastValueId =
            insertValue(variableId = variableId, createdBy = userId, createdTime = instant)
        return instant
      }

      // 1970-01-01: Document created by cannedInternalUserId

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
              createdTime = day1SavedVersionTimestamp)
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
              createdTime = day3SavedVersionTimestamp1)

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
              variableManifestId = variableManifestId2)

      // 2023-01-04: two edits by user 1, two edits by user 2, two edits by user 1. Should collapse
      // to two history entries, one for each user.
      insertNextValue(Duration.ofDays(1), userId1)
      insertNextValue(Duration.ofSeconds(1), userId1)
      insertNextValue(Duration.ofSeconds(1), userId2)
      val day4User2Timestamp = insertNextValue(Duration.ofSeconds(1), userId2)
      insertNextValue(Duration.ofSeconds(1), userId1)
      val day4User1Timestamp = insertNextValue(Duration.ofSeconds(1), userId1)

      mockMvc
          .get(historyPath())
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
                       "variableManifestId": $cannedVariableManifestId,
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
                       "variableManifestId": $cannedVariableManifestId,
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
                       "createdBy": $cannedInternalUserId,
                       "createdTime": "${Instant.EPOCH}"
                     }
                  ],
                  "status": "ok"
                }"""
                  .trimIndent())
    }

    @Test
    fun `returns not found error for nonexistent document`() {
      mockMvc.get(historyPath(1)).andExpect { status { isNotFound() } }
    }
  }

  @Nested
  inner class UpgradeManifest {
    private fun path(documentId: Any = cannedDocumentId) = "/api/v1/pdds/$documentId/upgrade"

    private fun payload(manifestId: Any) = """{ "variableManifestId": $manifestId }"""

    @Test
    fun `upgrades empty document`() {
      insertVariableManifestEntry(insertTextVariable())
      val newManifestId = insertVariableManifest()
      insertVariableManifestEntry(insertTextVariable(), manifestId = newManifestId)

      mockMvc.post(path()) { content = payload(newManifestId) }.andExpect { status { isOk() } }

      assertEquals(
          newManifestId,
          documentsDao.fetchOneById(cannedDocumentId)?.variableManifestId,
          "Manifest ID")
    }

    // This just tests that the controller calls the code that inserts the needed values; the
    // logic for calculating the needed values is tested in DocumentUpgradeCalculatorTest.
    @Test
    fun `inserts new values for replacement variables`() {
      val oldVariableId = insertVariableManifestEntry(insertNumberVariable())
      val newManifestId = insertVariableManifest()
      val newVariableId =
          insertVariableManifestEntry(
              insertTextVariable(
                  insertVariable(type = VariableType.Text, replacesVariableId = oldVariableId)),
              manifestId = newManifestId)
      insertValue(variableId = oldVariableId, numberValue = BigDecimal.ONE, citation = "citation")

      mockMvc.post(path()) { content = payload(newManifestId) }.andExpect { status { isOk() } }

      assertEquals(
          newManifestId,
          documentsDao.fetchOneById(cannedDocumentId)?.variableManifestId,
          "Manifest ID")

      assertEquals(
          listOf(
              VariableValuesRow(
                  citation = "citation",
                  createdBy = requestUser?.id,
                  createdTime = Instant.EPOCH,
                  documentId = cannedDocumentId,
                  isDeleted = false,
                  listPosition = 0,
                  textValue = "1",
                  variableId = newVariableId,
                  variableTypeId = VariableType.Text,
              )),
          variableValuesDao.fetchByVariableId(newVariableId).map { it.copy(id = null) },
          "Should have inserted new value")
    }

    @Test
    fun `returns not found error for nonexistent document`() {
      mockMvc
          .post(path(123)) { content = payload(insertVariableManifest()) }
          .andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns not found error for nonexistent manifest`() {
      mockMvc
          .post(path()) { content = payload(cannedVariableManifestId.value + 1) }
          .andExpect { status { isNotFound() } }
    }

    @Test
    fun `returns conflict error if manifest is for different methodology`() {
      insertVariableManifestEntry(insertTextVariable())
      val otherMethodologyId = insertMethodology()
      val otherMethodologyManifestId = insertVariableManifest(methodologyId = otherMethodologyId)
      insertVariableManifestEntry(insertTextVariable(), manifestId = otherMethodologyManifestId)

      mockMvc
          .post(path()) { content = payload(otherMethodologyManifestId) }
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
