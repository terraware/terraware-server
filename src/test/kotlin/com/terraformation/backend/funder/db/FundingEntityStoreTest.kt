package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.SimpleProjectModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.records.FundingEntityProjectsRecord
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val store by lazy { FundingEntityStore(dslContext) }

  private val fundingEntityId by lazy { insertFundingEntity() }

  @BeforeEach
  fun setUp() {
    every { user.canReadFundingEntities() } returns true
  }

  @Test
  fun `fetchOneById requires user to be able to read funding entities`() {
    every { user.canReadFundingEntities() } returns false

    assertThrows<AccessDeniedException> { store.fetchOneById(fundingEntityId) }
  }

  @Test
  fun `fetchOneById throws exception when entity doesn't exist`() {
    assertThrows<FundingEntityNotFoundException> { store.fetchOneById(FundingEntityId(1020)) }
  }

  @Test
  fun `fetchOneById returns correct funding entity`() {
    assertTrue(store.fetchOneById(fundingEntityId).name.startsWith("TestFundingEntity"))
  }

  @Test
  fun `fetchOneById retrieves entity with projects`() {
    insertOrganization()
    val namePrefix = "FetchOneEntityProject"
    val projectId1 = insertProject(name = "${namePrefix}1")
    val projectId2 = insertProject(name = "${namePrefix}2")

    assertTableEmpty(FUNDING_ENTITY_PROJECTS)

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
        ))

    assertEquals(
        listOf(
            SimpleProjectModel(projectId1, "${namePrefix}1"),
            SimpleProjectModel(projectId2, "${namePrefix}2")),
        store.fetchOneById(fundingEntityId).projects)
  }

  @Test
  fun `fetchAll requires user to be able to read funding entities`() {
    every { user.canReadFundingEntities() } returns false

    assertThrows<AccessDeniedException> { store.fetchAll() }
  }

  @Test
  fun `fetchAll returns funding entities with and without projects, sorted correctly`() {
    insertOrganization()
    val namePrefix = "FetchAllEntitiesProject"
    val projectId1 = insertProject(name = "${namePrefix}1")
    val projectId2 = insertProject(name = "${namePrefix}2")

    insertFundingEntity() // noProjectsEntity
    val oneProjectEntity = insertFundingEntity()
    val multipleProjectEntity = insertFundingEntity()

    insertFundingEntityProject(oneProjectEntity, projectId1)
    insertFundingEntityProject(multipleProjectEntity, projectId2)
    insertFundingEntityProject(multipleProjectEntity, projectId1)

    val actualEntities = store.fetchAll()
    assertEquals(3, actualEntities.size, "Should have fetched 3 entities")
    assertEquals(
        emptyList<SimpleProjectModel>(),
        actualEntities[0].projects,
        "First entity should have no projects")
    assertEquals(
        listOf(SimpleProjectModel(projectId1, "${namePrefix}1")),
        actualEntities[1].projects,
        "Second entity should have one project")
    assertEquals(
        listOf(
            SimpleProjectModel(projectId1, "${namePrefix}1"),
            SimpleProjectModel(projectId2, "${namePrefix}2")),
        actualEntities[2].projects,
        "Third entity should have both projects")
  }
}
