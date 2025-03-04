package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.funder.db.FundingEntityExistsException
import com.terraformation.backend.funder.db.FundingEntityNotFoundException
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.db.FundingEntityUserStore
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private lateinit var fundingEntityStore: FundingEntityStore
  private lateinit var fundingEntityUserStore: FundingEntityUserStore
  private lateinit var fundingEntityId: FundingEntityId
  private lateinit var service: FundingEntityService

  @BeforeEach
  fun setUp() {
    fundingEntityStore = FundingEntityStore(dslContext)
    fundingEntityUserStore = FundingEntityUserStore(dslContext)
    service = FundingEntityService(clock, dslContext, fundingEntityStore, fundingEntityUserStore)

    fundingEntityId = insertFundingEntity()
    insertOrganization()

    every { user.canReadFundingEntities() } returns true
    every { user.canCreateFundingEntities() } returns true
    every { user.canDeleteFundingEntities() } returns true
    every { user.canUpdateFundingEntities() } returns true
    every { user.canUpdateFundingEntityProjects() } returns true
  }

  @Test
  fun `create requires user to be able to manage funding entities`() {
    every { user.canCreateFundingEntities() } returns false

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
  fun `update throws exception when no matching entity`() {
    assertThrows<FundingEntityNotFoundException> {
      service.update(
          FundingEntitiesRow(id = FundingEntityId(1093), name = "Missing Funding Entity"))
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
    every { user.canDeleteFundingEntities() } returns false

    assertThrows<AccessDeniedException> { service.deleteFundingEntity(fundingEntityId) }
  }

  @Test
  fun `delete throws exception if entity doesn't exist`() {
    assertThrows<FundingEntityNotFoundException> {
      service.deleteFundingEntity(FundingEntityId(1097))
    }
  }

  @Test
  fun `delete successfully removes funding entity`() {
    service.deleteFundingEntity(fundingEntityId)

    assertEquals(0, fundingEntitiesDao.findAll().size)
  }

  @Test
  fun `event listener exists for UserDeletionStartedEvent`() {
    assertIsEventListener<UserDeletionStartedEvent>(service)
  }

  @Test
  fun `user deletion removes row from funding_entity_users table`() {
    insertFundingEntityUser(fundingEntityId, currentUser().userId)

    val event = UserDeletionStartedEvent(currentUser().userId)

    service.on(event)

    assertNull(
        dslContext
            .select(FUNDING_ENTITY_USERS.USER_ID)
            .from(FUNDING_ENTITY_USERS)
            .where(FUNDING_ENTITY_USERS.USER_ID.eq(currentUser().userId))
            .and(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(fundingEntityId))
            .fetchOne())
  }
}
