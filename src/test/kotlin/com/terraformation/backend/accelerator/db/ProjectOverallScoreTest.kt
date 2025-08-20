package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ProjectOverallScoreModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.tables.records.ProjectOverallScoresRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectOverallScoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val store: ProjectOverallScoreStore by lazy {
    ProjectOverallScoreStore(clock, dslContext)
  }
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(user.userId)
    projectId = insertProject()

    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
  }

  @Nested
  inner class Fetch {
    @Test
    fun `empty result if no row inserted`() {
      val result = store.fetch(projectId)

      assertEquals(
          ProjectOverallScoreModel(projectId = projectId),
          result,
      )
    }

    @Test
    fun `queries by projectId`() {
      val otherUser = insertUser()
      val otherProjectId = insertProject()

      insertProjectOverallScore(
          projectId,
          detailsUrl = URI("https://google.com"),
          overallScore = 1.5,
          summary = "summary 1",
          createdBy = otherUser,
          createdTime = Instant.ofEpochSecond(300),
      )

      insertProjectOverallScore(
          otherProjectId,
          detailsUrl = URI("https://dropbox.com"),
          overallScore = 2.5,
          summary = "summary 2",
          createdBy = user.userId,
          createdTime = Instant.ofEpochSecond(600),
      )

      assertEquals(
          ProjectOverallScoreModel(
              projectId = projectId,
              detailsUrl = URI("https://google.com"),
              overallScore = 1.5,
              summary = "summary 1",
              modifiedBy = otherUser,
              modifiedTime = Instant.ofEpochSecond(300),
          ),
          store.fetch(projectId),
          "fetch by original projectId",
      )

      assertEquals(
          ProjectOverallScoreModel(
              projectId = otherProjectId,
              detailsUrl = URI("https://dropbox.com"),
              overallScore = 2.5,
              summary = "summary 2",
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(600),
          ),
          store.fetch(otherProjectId),
          "fetch by other projectId",
      )
    }

    @Test
    fun `throws exception if no permission to read scores`() {
      deleteUserGlobalRole(user.userId, GlobalRole.TFExpert)
      assertThrows<AccessDeniedException> { store.fetch(projectId) }

      insertUserGlobalRole(user.userId, GlobalRole.ReadOnly)
      assertDoesNotThrow { store.fetch(projectId) }
    }
  }

  @Nested
  inner class UpdateScores {
    @Test
    fun `updates by projectId`() {
      clock.instant = Instant.ofEpochSecond(2000)

      val otherUser = insertUser()
      val otherProjectId = insertProject()

      insertProjectOverallScore(
          projectId,
          detailsUrl = URI("https://google.com"),
          overallScore = 1.5,
          summary = "summary 1",
          createdBy = otherUser,
          createdTime = Instant.ofEpochSecond(300),
      )

      store.update(projectId) {
        it.copy(
            overallScore = -1.5,
            summary = null, // Set to null
            projectId = otherProjectId, // ignored by update
            modifiedBy = otherUser, // ignored by update
            modifiedTime = Instant.ofEpochSecond(300), // ignored by update
        )
      }

      // Create new project score
      store.update(otherProjectId) {
        ProjectOverallScoreModel(
            projectId = otherProjectId,
            detailsUrl = URI("https://dropbox.com"),
            summary = "new summary",
            overallScore = 0.0,
        )
      }

      assertTableEquals(
          setOf(
              ProjectOverallScoresRecord(
                  projectId = projectId,
                  detailsUrl = URI("https://google.com"),
                  overallScore = -1.5,
                  summary = null,
                  createdBy = otherUser,
                  createdTime = Instant.ofEpochSecond(300),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectOverallScoresRecord(
                  projectId = otherProjectId,
                  detailsUrl = URI("https://dropbox.com"),
                  overallScore = 0.0,
                  summary = "new summary",
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          )
      )
    }

    @Test
    fun `throws exception if no permission to update scores`() {
      deleteUserGlobalRole(user.userId, GlobalRole.TFExpert)
      assertThrows<AccessDeniedException> { store.update(projectId) { it } }

      insertUserGlobalRole(user.userId, GlobalRole.ReadOnly)
      assertThrows<AccessDeniedException> { store.update(projectId) { it } }
    }
  }
}
