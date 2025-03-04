package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.funder.db.FundingEntityExistsException
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private lateinit var fundingEntityStore: FundingEntityStore
  private lateinit var fundingEntityId: FundingEntityId
  private lateinit var service: FundingEntityService

  @BeforeEach
  fun setUp() {
    fundingEntityStore = FundingEntityStore(dslContext)
    service = FundingEntityService(clock, dslContext, fundingEntityStore)

    fundingEntityId = insertFundingEntity()
    insertOrganization()

    every { user.canReadFundingEntities() } returns true
    every { user.canCreateFundingEntity() } returns true
    every { user.canDeleteFundingEntity() } returns true
    every { user.canUpdateFundingEntities() } returns true
    every { user.canUpdateFundingEntityProjects() } returns true
  }

  @Test
  fun `create requires user to be able to manage funding entities`() {
    every { user.canCreateFundingEntity() } returns false

    assertThrows<AccessDeniedException> { service.create("Some Other Entity") }
  }

  @Test
  fun `create populates created and modified fields`() {
    val newTime = clock.instant().plusSeconds(1000)
    clock.instant = newTime

    val newUserId = insertUser()

    val name = "New Funding Entity"
    val row = FundingEntitiesRow(name = name)

    every { user.userId } returns newUserId

    val createdModel = service.create(row.name!!)

    val expected =
        row.copy(
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = createdModel.id,
            modifiedBy = newUserId,
            modifiedTime = clock.instant(),
            name = name,
        )
    // retrieving both row and model to check createdBy/modifiedBy as well as projects
    val actualRow = fundingEntitiesDao.fetchOneById(createdModel.id)!!
    val actualModel = fundingEntityStore.fetchOneById(createdModel.id)

    assertEquals(expected, actualRow)
    assertEquals(0, actualModel.projects!!.size)
  }

  @Test
  fun `create rejects duplicates by name`() {
    assertThrows<FundingEntityExistsException> { service.create("TestFundingEntity") }
  }

  @Test
  fun `create adds project rows`() {
    insertOrganization()
    val projectId1 = insertProject()
    val projectId2 = insertProject()
    val projectSet = setOf(projectId1, projectId2)

    val inserted = service.create("New Funding Entity with Projects", projectSet)

    val actual = fundingEntityStore.fetchOneById(inserted.id)

    assertEquals(2, actual.projects!!.size)
    assertEquals(projectSet, actual.projects!!.toSet())
  }

  @Test
  fun `update throws exception when no id attached`() {
    assertThrows<IllegalArgumentException> {
      service.update(FundingEntitiesRow(name = "Funding Entity without Id"))
    }
  }

  @Test
  fun `update throws exception if user has no permission to manage funding entities`() {
    every { user.canUpdateFundingEntities() } returns false

    assertThrows<AccessDeniedException> {
      service.update(FundingEntitiesRow(id = fundingEntityId, name = "Updated Funding Entity"))
    }
  }

  @Test
  fun `update throws exception when name conflict`() {
    insertFundingEntity("Existing Funding Entity")

    assertThrows<FundingEntityExistsException> {
      service.update(FundingEntitiesRow(id = fundingEntityId, name = "Existing Funding Entity"))
    }
  }

  @Test
  fun `update populates modifiedBy fields`() {
    val newTime = clock.instant().plusSeconds(1000)
    clock.instant = newTime

    val newUserId = insertUser()

    val updates =
        FundingEntitiesRow(
            id = fundingEntityId,
            name = "New Name",
        )
    val expected =
        updates.copy(
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            modifiedBy = newUserId,
            modifiedTime = newTime,
        )

    every { user.userId } returns newUserId

    service.update(updates)

    val actual = fundingEntitiesDao.fetchOneById(fundingEntityId)!!
    assertEquals(expected, actual)
  }

  @Test
  fun `update correctly adds new projects`() {
    val row = FundingEntitiesRow(id = fundingEntityId, name = "TestFundingEntity")
    val projectId1 = insertProject()

    assertEquals(0, fundingEntityProjectsDao.fetchByFundingEntityId(fundingEntityId).size)

    service.update(row, setOf(projectId1))

    assertEquals(1, fundingEntityProjectsDao.fetchByFundingEntityId(fundingEntityId).size)
  }

  @Test
  fun `update correctly removes projects`() {
    val row = FundingEntitiesRow(id = fundingEntityId, name = "TestFundingEntity")
    val projectId1 = insertProject()
    val projectId2 = insertProject()
    insertFundingEntityProject(fundingEntityId, projectId1)
    insertFundingEntityProject(fundingEntityId, projectId2)

    assertEquals(2, fundingEntityProjectsDao.fetchByFundingEntityId(fundingEntityId).size)

    service.update(row, emptySet(), setOf(projectId1))

    val actual =
        fundingEntityProjectsDao.fetchByFundingEntityId(fundingEntityId).map { it.projectId }
    assertEquals(1, actual.size)
    assertEquals(projectId2, actual.first())
  }

  @Test
  fun `update correctly adds and removes projects`() {
    val row = FundingEntitiesRow(id = fundingEntityId, name = "TestFundingEntity")
    val projectId1 = insertProject()
    val projectId2 = insertProject()
    val projectId3 = insertProject()

    insertFundingEntityProject(fundingEntityId, projectId1)
    insertFundingEntityProject(fundingEntityId, projectId2)

    assertEquals(2, fundingEntityProjectsDao.fetchByFundingEntityId(fundingEntityId).size)

    service.update(row, setOf(projectId3), setOf(projectId1))

    val actual =
        fundingEntityProjectsDao.fetchByFundingEntityId(fundingEntityId).map { it.projectId }
    assertEquals(2, actual.size)
    assertEquals(listOf(projectId2, projectId3), actual)
  }

  @Test
  fun `delete throws exception if user can't manage funding entities`() {
    every { user.canDeleteFundingEntity() } returns false

    assertThrows<AccessDeniedException> { service.deleteFundingEntity(fundingEntityId) }
  }

  @Test
  fun `delete successfully removes funding entity`() {
    service.deleteFundingEntity(fundingEntityId)

    assertEquals(0, fundingEntitiesDao.findAll().size)
  }
}
