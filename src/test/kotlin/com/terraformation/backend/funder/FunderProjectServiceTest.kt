package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.funder.db.PublishedProjectDetailsStore
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import com.terraformation.backend.funder.model.PublishedProjectNameModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FunderProjectServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  val publishedProjectDetailsStore: PublishedProjectDetailsStore = mockk()
  lateinit var projectId: ProjectId

  private val service: FunderProjectService by lazy {
    FunderProjectService(
        publishedProjectDetailsStore = publishedProjectDetailsStore,
    )
  }

  @BeforeEach
  fun setup() {
    insertOrganization()
    projectId = insertProject()

    val existingDetails = FunderProjectDetailsModel(projectId = projectId)
    val existingProject = PublishedProjectNameModel(projectId = projectId)

    every { publishedProjectDetailsStore.fetchOneById(projectId) } returns existingDetails
    every { publishedProjectDetailsStore.fetchAll() } returns listOf(existingProject)
  }

  @Nested
  inner class FetchAll {
    @Test
    fun `throws error if user can't read published projects`() {
      assertThrows<AccessDeniedException> { service.fetchAll() }
    }

    @Test
    fun `does not throw error if user can read project funder details`() {
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      // actual values are tested in other tests
      assertDoesNotThrow { service.fetchAll() }
    }
  }

  @Nested
  inner class FetchByProjectId {
    @Test
    fun `throws error if user can't read project funder details`() {
      val funderId = insertUser(type = UserType.Funder)
      switchToUser(funderId)
      assertThrows<ProjectNotFoundException> { service.fetchByProjectId(projectId) }
    }

    @Test
    fun `does not throw error if user can read project funder details`() {
      insertFundingEntity()
      insertFundingEntityProject()
      val funderId = insertUser(type = UserType.Funder)
      insertFundingEntityUser()
      switchToUser(funderId)

      // actual values are tested in other tests
      assertDoesNotThrow { service.fetchByProjectId(projectId) }
    }
  }

  @Nested
  inner class FetchListByProjectIds {
    @Test
    fun `retrieves correct amount of project details`() {
      insertFundingEntity()
      insertFundingEntityProject()
      val projectId2 = insertProject()
      insertFundingEntityProject()
      val projectId3 = insertProject()
      insertFundingEntityProject()
      val funderId = insertUser(type = UserType.Funder)
      insertFundingEntityUser()
      switchToUser(funderId)

      val existingDetails2 = FunderProjectDetailsModel(projectId = projectId2)

      every { publishedProjectDetailsStore.fetchOneById(projectId2) } returns existingDetails2
      // unpublished projects should be excluded in final result
      every { publishedProjectDetailsStore.fetchOneById(projectId3) } returns null

      assertEquals(
          2,
          service.fetchListByProjectIds(setOf(projectId, projectId2)).size,
          "Expected 2 projects returned")
    }
  }

  @Nested
  inner class PublishProjectProfile {
    @Test
    fun `throws error if user can't publish project funder details`() {
      assertThrows<AccessDeniedException> {
        service.publishProjectProfile(FunderProjectDetailsModel(projectId = projectId))
      }
    }
  }
}
