package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.records.FundingEntityProjectsRecord
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.funder.model.FundingProjectModel
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store by lazy { FundingEntityStore(dslContext) }

  private val otherUserId by lazy { insertUser() }
  private val organizationId by lazy { insertOrganization(createdBy = otherUserId) }

  @BeforeEach
  fun setUp() {
    every { user.canReadFundingEntity(any()) } returns true
    every { user.canReadFundingEntities() } returns true
    every { user.canReadProject(any()) } returns true
  }

  @Test
  fun `fetchOneById requires user to be able to read funding entities`() {
    val fundingEntityId = insertFundingEntity()
    every { user.canReadFundingEntity(fundingEntityId) } returns false
    assertThrows<FundingEntityNotFoundException> { store.fetchOneById(fundingEntityId) }
  }

  @Test
  fun `fetchOneById throws exception when entity doesn't exist`() {
    assertThrows<FundingEntityNotFoundException> { store.fetchOneById(FundingEntityId(1020)) }
  }

  @Test
  fun `fetchOneById returns correct funding entity`() {
    val fundingEntityId = insertFundingEntity()
    assertTrue(store.fetchOneById(fundingEntityId).name.startsWith("TestFundingEntity"))
  }

  @Test
  fun `fetchOneById retrieves entity with projects`() {
    val namePrefix = "FetchOneEntityProject"
    val dealNamePrefix = "${namePrefix}Deal"
    val projectId1 = insertProject(name = "${namePrefix}1", organizationId = organizationId)
    val projectId2 = insertProject(name = "${namePrefix}2", organizationId = organizationId)
    insertProjectAcceleratorDetails(dealName = "${dealNamePrefix}1", projectId = projectId1)
    insertProjectAcceleratorDetails(dealName = "${dealNamePrefix}2", projectId = projectId2)

    assertTableEmpty(FUNDING_ENTITY_PROJECTS)

    val fundingEntityId = insertFundingEntity()
    insertFundingEntityProject(fundingEntityId, projectId1)
    insertFundingEntityProject(fundingEntityId, projectId2)

    assertTableEquals(
        listOf(
            FundingEntityProjectsRecord(
                fundingEntityId = fundingEntityId,
                projectId = projectId1,
            ),
            FundingEntityProjectsRecord(
                fundingEntityId = fundingEntityId,
                projectId = projectId2,
            ),
        )
    )

    assertEquals(
        listOf(
            FundingProjectModel(projectId = projectId1, dealName = "${dealNamePrefix}1"),
            FundingProjectModel(projectId = projectId2, dealName = "${dealNamePrefix}2"),
        ),
        store.fetchOneById(fundingEntityId).projects,
    )
  }

  @Test
  fun `fetchAll requires user to be able to read funding entities`() {
    every { user.canReadFundingEntities() } returns false

    assertThrows<AccessDeniedException> { store.fetchAll() }
  }

  @Test
  fun `fetchAll returns funding entities with and without projects, sorted correctly`() {
    val organizationId = insertOrganization()
    val namePrefix = "FetchAllEntitiesProject"
    val dealNamePrefix = "${namePrefix}Deal"
    val projectId1 = insertProject(name = "${namePrefix}1", organizationId = organizationId)
    val projectId2 = insertProject(name = "${namePrefix}2", organizationId = organizationId)
    insertProjectAcceleratorDetails(dealName = "${dealNamePrefix}1", projectId = projectId1)
    insertProjectAcceleratorDetails(dealName = "${dealNamePrefix}2", projectId = projectId2)

    insertFundingEntity() // noProjectsEntity
    val oneProjectEntity = insertFundingEntity()
    val multipleProjectEntity = insertFundingEntity()

    insertFundingEntityProject(oneProjectEntity, projectId1)
    insertFundingEntityProject(multipleProjectEntity, projectId2)
    insertFundingEntityProject(multipleProjectEntity, projectId1)

    val actualEntities = store.fetchAll()
    assertEquals(3, actualEntities.size, "Should have fetched 3 entities")
    assertEquals(
        emptyList<FundingProjectModel>(),
        actualEntities[0].projects,
        "First entity should have no projects",
    )
    assertEquals(
        listOf(FundingProjectModel(projectId = projectId1, dealName = "${dealNamePrefix}1")),
        actualEntities[1].projects,
        "Second entity should have one project",
    )
    assertEquals(
        listOf(
            FundingProjectModel(projectId = projectId1, dealName = "${dealNamePrefix}1"),
            FundingProjectModel(projectId = projectId2, dealName = "${dealNamePrefix}2"),
        ),
        actualEntities[2].projects,
        "Third entity should have both projects",
    )
  }

  @Test
  fun `fetchByProjectId requires user to be able to read funding entities`() {
    every { user.canReadFundingEntities() } returns false

    assertThrows<AccessDeniedException> { store.fetchByProjectId(ProjectId(1)) }
  }

  @Test
  fun `fetchByProjectId requires user to be able to read project`() {
    insertOrganization()
    val projectId = insertProject()
    every { user.canReadProject(projectId) } returns false

    assertThrows<ProjectNotFoundException> { store.fetchByProjectId(projectId) }
  }

  @Test
  fun `fetchByProjectId returns empty list when no funding entities are attached to project`() {
    insertOrganization()
    val fundingEntity1 = insertFundingEntity()
    val projectId1 = insertProject()
    val projectId2 = insertProject()
    insertFundingEntityProject(fundingEntity1, projectId2)

    assertEquals(
        emptyList<FundingEntityModel>(),
        store.fetchByProjectId(projectId1),
        "Project should not be connected to any entities",
    )
  }

  @Test
  fun `fetchByProjectId returns correct funding entities`() {
    insertOrganization()
    val fundingEntity1 = insertFundingEntity(name = "Funding entity 1")
    val fundingEntity2 = insertFundingEntity(name = "Funding entity 2")
    val fundingEntity3 = insertFundingEntity(name = "Funding entity 3")
    val projectId1 = insertProject()
    val projectId2 = insertProject()
    insertFundingEntityProject(fundingEntity1, projectId1)
    insertFundingEntityProject(fundingEntity2, projectId1)
    insertFundingEntityProject(fundingEntity3, projectId2)

    assertEquals(
        listOf(
            FundingEntityModel(
                id = fundingEntity1,
                name = "Funding entity 1",
                createdTime = Instant.EPOCH,
                modifiedTime = Instant.EPOCH,
                projects = listOf(FundingProjectModel(projectId = projectId1, dealName = "")),
            ),
            FundingEntityModel(
                id = fundingEntity2,
                name = "Funding entity 2",
                createdTime = Instant.EPOCH,
                modifiedTime = Instant.EPOCH,
                projects = listOf(FundingProjectModel(projectId = projectId1, dealName = "")),
            ),
        ),
        store.fetchByProjectId(projectId1),
        "Project should be connected to both entities",
    )
  }
}
