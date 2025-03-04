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
import com.terraformation.backend.db.funder.tables.records.FundingEntitiesRecord
import com.terraformation.backend.db.funder.tables.records.FundingEntityProjectsRecord
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.funder.db.FundingEntityExistsException
import com.terraformation.backend.funder.db.FundingEntityNotFoundException
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.db.FundingEntityUserStore
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val fundingEntityStore by lazy { FundingEntityStore(dslContext) }
  private val fundingEntityUserStore by lazy { FundingEntityUserStore(dslContext) }
  private val service by lazy {
    FundingEntityService(clock, dslContext, fundingEntityStore, fundingEntityUserStore)
  }

  private val fundingEntityId by lazy { insertFundingEntity() }
  private lateinit var fundingEntityName: String

  @BeforeEach
  fun setUp() {
    insertOrganization()

    every { user.canReadFundingEntities() } returns true
    every { user.canCreateFundingEntities() } returns true
    every { user.canDeleteFundingEntities() } returns true
    every { user.canUpdateFundingEntities() } returns true
    every { user.canUpdateFundingEntityProjects() } returns true

    // has to be after permissions in order to read correctly
    fundingEntityName = fundingEntityStore.fetchOneById(fundingEntityId).name
  }

  @Test
  fun `create requires user to be able to manage funding entities`() {
    every { user.canCreateFundingEntities() } returns false

    assertThrows<AccessDeniedException> { service.create("Some Other Entity") }
  }

  @Test
  fun `create populates created and modified fields`() {
    val initialRecord =
        FundingEntitiesRecord(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            id = fundingEntityId,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = fundingEntityName,
        )
    val newTime = clock.instant().plusSeconds(1000)
    clock.instant = newTime

    val newUserId = insertUser()
    val name = "New Funding Entity ${UUID.randomUUID()}"

    every { user.userId } returns newUserId

    val createdModel = service.create(name)

    assertTableEquals(
        listOf(
            initialRecord,
            FundingEntitiesRecord(
                createdBy = user.userId,
                createdTime = clock.instant(),
                id = createdModel.id,
                modifiedBy = newUserId,
                modifiedTime = clock.instant(),
                name = name,
            )))
    assertTableEmpty(FUNDING_ENTITY_PROJECTS)
  }

  @Test
  fun `create rejects duplicates by name`() {
    assertThrows<FundingEntityExistsException> { service.create(fundingEntityName) }
  }

  @Test
  fun `create adds project rows`() {
    insertOrganization()
    val projectId1 = insertProject()
    val projectId2 = insertProject()
    val projectSet = setOf(projectId1, projectId2)

    val inserted =
        service.create("New Funding Entity with Projects ${UUID.randomUUID()}", projectSet)

    assertTableEquals(
        listOf(
            FundingEntityProjectsRecord(fundingEntityId = inserted.id, projectId = projectId1),
            FundingEntityProjectsRecord(fundingEntityId = inserted.id, projectId = projectId2),
        ))
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

    val newName = "New Name ${UUID.randomUUID()}"
    val updates =
        FundingEntitiesRow(
            id = fundingEntityId,
            name = newName,
        )
    val expected =
        FundingEntitiesRecord(
            id = fundingEntityId,
            name = newName,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            modifiedBy = newUserId,
            modifiedTime = newTime,
        )

    every { user.userId } returns newUserId

    service.update(updates)

    assertTableEquals(expected)
  }

  @Test
  fun `update correctly adds new projects`() {
    val row = FundingEntitiesRow(id = fundingEntityId, name = fundingEntityName)
    val projectId1 = insertProject()

    assertTableEmpty(FUNDING_ENTITY_PROJECTS)

    service.update(row, setOf(projectId1))

    assertTableEquals(
        FundingEntityProjectsRecord(fundingEntityId = fundingEntityId, projectId = projectId1))
  }

  @Test
  fun `update correctly removes projects`() {
    val row = FundingEntitiesRow(id = fundingEntityId, name = fundingEntityName)
    val projectId1 = insertProject()
    insertFundingEntityProject(fundingEntityId)
    val projectId2 = insertProject()
    insertFundingEntityProject(fundingEntityId)

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

    service.update(row, emptySet(), setOf(projectId1))

    assertTableEquals(
        FundingEntityProjectsRecord(
            fundingEntityId = fundingEntityId,
            projectId = projectId2,
        ),
    )
  }

  @Test
  fun `update correctly adds and removes projects`() {
    val row = FundingEntitiesRow(id = fundingEntityId, name = fundingEntityName)
    val projectId1 = insertProject()
    insertFundingEntityProject(fundingEntityId)
    val projectId2 = insertProject()
    insertFundingEntityProject(fundingEntityId)
    val projectId3 = insertProject()

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

    service.update(row, setOf(projectId3), setOf(projectId1))

    assertTableEquals(
        listOf(
            FundingEntityProjectsRecord(
                fundingEntityId = fundingEntityId,
                projectId = projectId3,
            ),
            FundingEntityProjectsRecord(
                fundingEntityId = fundingEntityId,
                projectId = projectId2,
            ),
        ))
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

    assertTableEmpty(FUNDING_ENTITIES)
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
