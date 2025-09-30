package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.model.ExistingDocumentModel
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DocumentStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()

  private val store: DocumentStore by lazy {
    DocumentStore(clock, documentSavedVersionsDao, documentsDao, dslContext, documentTemplatesDao)
  }

  private lateinit var documentTemplateId1: DocumentTemplateId
  private lateinit var documentTemplateId2: DocumentTemplateId
  private lateinit var projectId1: ProjectId
  private lateinit var projectId2: ProjectId
  private lateinit var variableManifestId1: VariableManifestId
  private lateinit var variableManifestId2: VariableManifestId

  @BeforeEach
  fun setUp() {
    insertOrganization()

    documentTemplateId1 = insertDocumentTemplate(name = "Template 1")
    documentTemplateId2 = insertDocumentTemplate(name = "Template 2")

    projectId1 = insertProject(name = "Project 1")
    projectId2 = insertProject(name = "Project 2")

    variableManifestId1 = insertVariableManifest(documentTemplateId = documentTemplateId1)
    variableManifestId2 = insertVariableManifest(documentTemplateId = documentTemplateId2)

    every { user.canReadDocument(any()) } returns true
  }

  @Nested
  inner class ExistingDocumentFetches {
    private lateinit var documentId1: DocumentId
    private lateinit var documentId2: DocumentId
    private lateinit var documentId3: DocumentId
    private lateinit var existingDocument1: ExistingDocumentModel
    private lateinit var existingDocument2: ExistingDocumentModel
    private lateinit var existingDocument3: ExistingDocumentModel
    private lateinit var now: Instant
    private lateinit var savedVersionId2: DocumentSavedVersionId
    private lateinit var userId: UserId

    @BeforeEach
    fun setUp() {
      documentId1 =
          insertDocument(
              documentTemplateId = documentTemplateId1,
              internalComment = "A comment",
              name = "Project $projectId1 Feasibility Study",
              projectId = projectId1,
              variableManifestId = variableManifestId1,
          )
      documentId2 =
          insertDocument(
              documentTemplateId = documentTemplateId2,
              name = "Project $projectId1 Project Summary",
              projectId = projectId1,
              variableManifestId = variableManifestId2,
          )
      documentId3 =
          insertDocument(
              documentTemplateId = documentTemplateId1,
              name = "Project $projectId2 Feasibility Study",
              projectId = projectId2,
              variableManifestId = variableManifestId1,
          )

      insertSavedVersion(documentId = documentId2, maxValueId = VariableValueId(1))
      savedVersionId2 =
          insertSavedVersion(documentId = documentId2, maxValueId = VariableValueId(2))

      userId = currentUser().userId
      now = clock.instant

      existingDocument1 =
          ExistingDocumentModel(
              createdBy = userId,
              createdTime = now,
              documentTemplateId = documentTemplateId1,
              documentTemplateName = "Template 1",
              id = documentId1,
              internalComment = "A comment",
              lastSavedVersionId = null,
              modifiedBy = userId,
              modifiedTime = now,
              name = "Project $projectId1 Feasibility Study",
              ownedBy = userId,
              projectDealName = null,
              projectId = projectId1,
              projectName = "Project 1",
              status = DocumentStatus.Draft,
              variableManifestId = variableManifestId1,
          )
      existingDocument2 =
          ExistingDocumentModel(
              createdBy = userId,
              createdTime = now,
              documentTemplateId = documentTemplateId2,
              documentTemplateName = "Template 2",
              id = documentId2,
              lastSavedVersionId = savedVersionId2,
              modifiedBy = userId,
              modifiedTime = now,
              name = "Project $projectId1 Project Summary",
              ownedBy = userId,
              projectDealName = null,
              projectId = projectId1,
              projectName = "Project 1",
              status = DocumentStatus.Draft,
              variableManifestId = variableManifestId2,
          )
      existingDocument3 =
          ExistingDocumentModel(
              createdBy = userId,
              createdTime = now,
              documentTemplateId = documentTemplateId1,
              documentTemplateName = "Template 1",
              id = documentId3,
              lastSavedVersionId = null,
              modifiedBy = userId,
              modifiedTime = now,
              name = "Project $projectId2 Feasibility Study",
              ownedBy = userId,
              projectDealName = null,
              projectId = projectId2,
              projectName = "Project 2",
              status = DocumentStatus.Draft,
              variableManifestId = variableManifestId1,
          )
    }

    @Test
    fun `fetches all documents`() {
      assertSetEquals(
          setOf(existingDocument1, existingDocument2, existingDocument3),
          store.fetchAll().toSet(),
      )
    }

    @Test
    fun `fetches all documents for a given project ID`() {
      assertSetEquals(
          setOf(existingDocument1, existingDocument2),
          store.fetchByProjectId(projectId1).toSet(),
      )
    }

    @Test
    fun `returns an empty list for a project ID with no documents`() {
      val projectIdWithNoDocuments = insertProject(name = "Empty Project")
      assertEquals(
          emptyList<ExistingDocumentModel>(),
          store.fetchByProjectId(projectIdWithNoDocuments),
      )
    }

    @Test
    fun `fetches the document for a given document ID`() {
      assertEquals(existingDocument3, store.fetchOneById(documentId3))
    }
  }
}
