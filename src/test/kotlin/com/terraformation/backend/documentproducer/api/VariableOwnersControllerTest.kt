package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.tables.pojos.VariableOwnersRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

class VariableOwnersControllerTest : ControllerIntegrationTest() {
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    projectId = insertProject()
  }

  @Nested
  inner class ListVariableOwners {
    private fun path(project: ProjectId = projectId) =
        "/api/v1/document-producer/projects/$project/owners"

    @Nested
    inner class WithPermission {
      @BeforeEach
      fun setUp() {
        insertUserGlobalRole(role = GlobalRole.TFExpert)
      }

      @Test
      fun `returns owner user IDs for variables with owners`() {
        val ownerId1 = insertUser()
        val ownerId2 = insertUser()

        val sectionId1 = insertSectionVariable()
        val sectionId2 = insertSectionVariable()
        val sectionId3 = insertSectionVariable()

        insertVariableOwner(sectionId1, ownerId1)
        insertVariableOwner(sectionId2, ownerId2)

        // Owners from other projects shouldn't be returned.
        insertProject()
        insertVariableOwner(sectionId3, ownerId1)

        mockMvc
            .get(path())
            .andExpectJson(
                """
                  {
                    "variables": [
                      {
                        "ownedBy": $ownerId1,
                        "variableId": $sectionId1
                      },
                      {
                        "ownedBy": $ownerId2,
                        "variableId": $sectionId2
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

    @Test
    fun `returns error if user has no permission to list owners`() {
      insertOrganizationUser()
      mockMvc.get(path()).andExpect { status { isForbidden() } }
    }
  }

  @Nested
  inner class UpdateVariableOwner {
    private lateinit var sectionId: VariableId
    private lateinit var otherSectionId: VariableId

    private fun path(
        projectId: ProjectId = inserted.projectId,
        variableId: VariableId = sectionId,
    ) = "/api/v1/document-producer/projects/$projectId/owners/$variableId"

    @BeforeEach
    fun setUp() {
      sectionId = insertSectionVariable()

      // Should only update the owner of the specified variable, even if other variables exist.
      otherSectionId = insertSectionVariable()
    }

    @Nested
    inner class WithPermission {
      @BeforeEach
      fun setUp() {
        insertUserGlobalRole(role = GlobalRole.TFExpert)
      }

      @Test
      fun `sets initial owner`() {
        mockMvc
            .put(path()) { content = """{"ownedBy": ${user.userId}}""" }
            .andExpect { status { isOk() } }

        assertEquals(
            listOf(VariableOwnersRow(inserted.projectId, sectionId, user.userId)),
            variableOwnersDao.findAll(),
            "New owner should have been stored in database",
        )
      }

      @Test
      fun `updates owner if one already exists`() {
        val newOwnerId = insertUser()
        insertVariableOwner(sectionId, user.userId)
        insertVariableOwner(otherSectionId, user.userId)

        mockMvc
            .put(path()) { content = """{"ownedBy": $newOwnerId}""" }
            .andExpect { status { isOk() } }

        assertEquals(
            setOf(
                VariableOwnersRow(inserted.projectId, sectionId, newOwnerId),
                VariableOwnersRow(inserted.projectId, otherSectionId, user.userId),
            ),
            variableOwnersDao.findAll().toSet(),
            "New owner should have been stored in database",
        )
      }

      @Test
      fun `removes existing owner`() {
        insertVariableOwner(sectionId, user.userId)
        insertVariableOwner(otherSectionId, user.userId)

        mockMvc.put(path()) { content = """{"ownedBy": null}""" }.andExpect { status { isOk() } }

        assertEquals(
            listOf(VariableOwnersRow(inserted.projectId, otherSectionId, user.userId)),
            variableOwnersDao.findAll(),
            "Should have deleted existing owner",
        )
      }

      @Test
      fun `returns error if project does not exist`() {
        mockMvc
            .put(path(projectId = ProjectId(0))) { content = """{"ownedBy": ${user.userId}}""" }
            .andExpect { status { isNotFound() } }
      }

      @Test
      fun `returns error if variable does not exist`() {
        mockMvc
            .put(path(variableId = VariableId(0))) { content = """{"ownedBy": ${user.userId}}""" }
            .andExpect { status { isNotFound() } }
      }
    }

    @Test
    fun `returns error if user has no permission to update owner`() {
      insertOrganizationUser()

      mockMvc
          .put(path(variableId = VariableId(0))) { content = """{"ownedBy": ${user.userId}}""" }
          .andExpect { status { isForbidden() } }
    }
  }
}
