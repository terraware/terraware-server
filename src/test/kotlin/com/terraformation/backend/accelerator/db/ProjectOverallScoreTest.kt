package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ProjectOverallScoreModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.tables.records.ProjectOverallScoresRecord
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectOverallScoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: ProjectOverallScoreStore by lazy {
    ProjectOverallScoreStore(clock, dslContext)
  }
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    projectId = insertProject()

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectScores(any()) } returns true
    every { user.canUpdateProjectScores(any()) } returns true
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
          createdTime = Instant.ofEpochSecond(300))

      insertProjectOverallScore(
          otherProjectId,
          detailsUrl = URI("https://dropbox.com"),
          overallScore = 2.5,
          summary = "summary 2",
          createdBy = user.userId,
          createdTime = Instant.ofEpochSecond(600))

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
          "fetch by original projectId")

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
          "fetch by other projectId")
    }

    @Test
    fun `throws exception if no permission to read scores`() {
      every { user.canReadProjectScores(projectId) } returns false
      assertThrows<AccessDeniedException> { store.fetch(projectId) }
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
          createdTime = Instant.ofEpochSecond(300))

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
          ))
    }

    @Test
    fun `throws exception if no permission to update scores`() {
      every { user.canUpdateProjectScores(projectId) } returns false

      assertThrows<AccessDeniedException> { store.update(projectId) { it } }
    }
  }
}
