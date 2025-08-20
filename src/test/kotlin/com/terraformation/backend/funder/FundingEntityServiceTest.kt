package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.db.funder.tables.records.FundingEntitiesRecord
import com.terraformation.backend.db.funder.tables.records.FundingEntityProjectsRecord
import com.terraformation.backend.db.funder.tables.records.FundingEntityUsersRecord
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.funder.db.EmailExistsException
import com.terraformation.backend.funder.db.FundingEntityExistsException
import com.terraformation.backend.funder.db.FundingEntityNotFoundException
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.db.FundingEntityUserStore
import com.terraformation.backend.funder.event.FunderInvitedToFundingEntityEvent
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException

class FundingEntityServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  @Autowired private lateinit var config: TerrawareServerConfig
  private val clock = TestClock()
  private val fundingEntityStore by lazy { FundingEntityStore(dslContext) }
  private val fundingEntityUserStore by lazy { FundingEntityUserStore(dslContext) }
  private val parentStore by lazy { ParentStore(dslContext) }
  private val organizationStore by lazy {
    OrganizationStore(clock, dslContext, organizationsDao, publisher)
  }
  private val publisher by lazy { TestEventPublisher() }
  private val userStore by lazy {
    UserStore(
        clock,
        config,
        dslContext,
        mockk(),
        InMemoryKeycloakAdminClient(),
        dummyKeycloakInfo(),
        organizationStore,
        parentStore,
        PermissionStore(dslContext),
        publisher,
        usersDao,
    )
  }
  private val service by lazy {
    FundingEntityService(
        clock,
        dslContext,
        fundingEntityStore,
        fundingEntityUserStore,
        publisher,
        userStore,
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()

    every { user.canReadFundingEntity(any()) } returns true
    every { user.canReadFundingEntities() } returns true
    every { user.canCreateFundingEntities() } returns true
    every { user.canDeleteFundingEntities() } returns true
    every { user.canListFundingEntityUsers(any()) } returns true
    every { user.canUpdateFundingEntities() } returns true
    every { user.canUpdateFundingEntityProjects() } returns true
    every { user.canDeleteFunder(any()) } returns true
    every { user.canUpdateFundingEntityUsers(any()) } returns true
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
    val name = "New Funding Entity ${UUID.randomUUID()}"

    every { user.userId } returns newUserId

    val createdModel = service.create(name)

    assertTableEquals(
        FundingEntitiesRecord(
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = createdModel.id,
            modifiedBy = newUserId,
            modifiedTime = clock.instant(),
            name = name,
        )
    )
    assertTableEmpty(FUNDING_ENTITY_PROJECTS)
  }

  @Test
  fun `create rejects duplicates by name`() {
    val name = "Duplicate Name ${UUID.randomUUID()}"
    insertFundingEntity(name)
    assertThrows<FundingEntityExistsException> { service.create(name) }
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
        )
    )
  }

  @Test
  fun `update throws exception when no id attached`() {
    assertThrows<IllegalArgumentException> {
      service.update(FundingEntitiesRow(name = "Funding Entity without Id"))
    }
  }

  @Test
  fun `update throws exception if user has no permission to manage funding entities`() {
    val fundingEntityId = insertFundingEntity()
    every { user.canUpdateFundingEntities() } returns false

    assertThrows<AccessDeniedException> {
      service.update(FundingEntitiesRow(id = fundingEntityId, name = "Updated Funding Entity"))
    }
  }

  @Test
  fun `update throws exception when name conflict`() {
    val firstEntityId = insertFundingEntity()
    insertFundingEntity("Existing Funding Entity")

    assertThrows<FundingEntityExistsException> {
      service.update(FundingEntitiesRow(id = firstEntityId, name = "Existing Funding Entity"))
    }
  }

  @Test
  fun `update throws exception when no matching entity`() {
    assertThrows<FundingEntityNotFoundException> {
      service.update(
          FundingEntitiesRow(id = FundingEntityId(1093), name = "Missing Funding Entity")
      )
    }
  }

  @Test
  fun `update populates modifiedBy fields`() {
    val fundingEntityId = insertFundingEntity()

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
    val fundingEntityName = "Entity Name ${UUID.randomUUID()}"
    val fundingEntityId = insertFundingEntity(fundingEntityName)

    val row = FundingEntitiesRow(id = fundingEntityId, name = fundingEntityName)
    val projectId1 = insertProject()

    assertTableEmpty(FUNDING_ENTITY_PROJECTS)

    service.update(row, setOf(projectId1))

    assertTableEquals(
        FundingEntityProjectsRecord(fundingEntityId = fundingEntityId, projectId = projectId1)
    )
  }

  @Test
  fun `update correctly removes projects`() {
    val fundingEntityName = "Entity Name ${UUID.randomUUID()}"
    val fundingEntityId = insertFundingEntity(fundingEntityName)

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
        )
    )

    service.update(row, setOf(projectId2))

    assertTableEquals(
        FundingEntityProjectsRecord(
            fundingEntityId = fundingEntityId,
            projectId = projectId2,
        ),
    )
  }

  @Test
  fun `update correctly adds and removes projects`() {
    val fundingEntityName = "Entity Name ${UUID.randomUUID()}"
    val fundingEntityId = insertFundingEntity(fundingEntityName)

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
        )
    )

    service.update(row, setOf(projectId3, projectId2))

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
        )
    )
  }

  @Test
  fun `delete throws exception if user can't manage funding entities`() {
    val fundingEntityId = insertFundingEntity()
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
  fun `delete successfully removes funding entity and deletes associated funders`() {
    val fundingEntityId = insertFundingEntity()
    val funderId1 = insertUser(type = UserType.Funder)
    val funderId2 = insertUser(type = UserType.Funder)
    insertFundingEntityUser(fundingEntityId, funderId1)
    insertFundingEntityUser(fundingEntityId, funderId2)

    service.deleteFundingEntity(fundingEntityId)

    val deletedFunder1 = usersDao.fetchOneById(funderId1)!!
    val deletedFunder2 = usersDao.fetchOneById(funderId2)!!

    assertTableEmpty(FUNDING_ENTITIES)
    assertTableEmpty(FUNDING_ENTITY_USERS)
    assertEquals("deleted:${funderId1}", deletedFunder1.email)
    assertEquals("deleted:${funderId2}", deletedFunder2.email)
  }

  @Test
  fun `event listener exists for UserDeletionStartedEvent`() {
    assertIsEventListener<UserDeletionStartedEvent>(service)
  }

  @Test
  fun `user deletion removes row from funding_entity_users table`() {
    every { user.canReadUser(any()) } returns true

    val fundingEntityId = insertFundingEntity()

    insertFundingEntityUser(fundingEntityId, currentUser().userId)

    val event = UserDeletionStartedEvent(currentUser().userId)

    service.on(event)

    assertTableEmpty(FUNDING_ENTITY_USERS)
  }

  @Test
  fun `deleteFunders throws exception if user cannot update entity users`() {
    every { user.canUpdateFundingEntityUsers(any()) } returns false
    val funderId = insertUser(type = UserType.Funder)
    val fundingEntityId = insertFundingEntity()
    insertFundingEntityUser(fundingEntityId, funderId)

    assertThrows<AccessDeniedException> {
      service.deleteFunders(fundingEntityId, userIds = setOf(funderId))
    }
  }

  @Test
  fun `deleteFunders publishes deleted events`() {
    val funderId1 = insertUser(type = UserType.Funder)
    val funderId2 = insertUser(type = UserType.Funder)
    val fundingEntityId = insertFundingEntity()
    insertFundingEntityUser(fundingEntityId, funderId1)
    insertFundingEntityUser(fundingEntityId, funderId2)

    service.deleteFunders(fundingEntityId, userIds = setOf(funderId1, funderId2))

    publisher.assertExactEventsPublished(
        setOf(UserDeletionStartedEvent(funderId1), UserDeletionStartedEvent(funderId2))
    )
  }

  @Nested
  inner class InviteFunder {
    @BeforeEach
    fun setUp() {
      every { user.canReadUser(any()) } returns true
    }

    @Test
    fun `inviteFunder throws exception if user can't update entity users`() {
      val fundingEntityId = insertFundingEntity()

      every { user.canUpdateFundingEntityUsers(fundingEntityId) } returns false

      assertThrows<AccessDeniedException> {
        service.inviteFunder(fundingEntityId, "email@example.com")
      }
    }

    @Test
    fun `inviteFunder throws exception if user exists and is not funder`() {
      val email = "email_${UUID.randomUUID()}@example.com"
      insertUser(email = email)
      val fundingEntityId = insertFundingEntity()

      assertThrows<EmailExistsException> { service.inviteFunder(fundingEntityId, email) }
    }

    @Test
    fun `inviteFunder throws exception if user is funder in different funding entity`() {
      val email = "email_${UUID.randomUUID()}@example.com"
      val funderId = insertUser(email = email, authId = null, type = UserType.Funder)
      val fundingEntityId1 = insertFundingEntity()
      val fundingEntityId2 = insertFundingEntity()
      insertFundingEntityUser(fundingEntityId1, funderId)

      assertThrows<EmailExistsException> { service.inviteFunder(fundingEntityId2, email) }
    }

    @Test
    fun `inviteFunder throws exception if user is funder and has already registered`() {
      val email = "email_${UUID.randomUUID()}@example.com"
      val funderId = insertUser(email = email, type = UserType.Funder)
      val fundingEntityId = insertFundingEntity()
      insertFundingEntityUser(fundingEntityId, funderId)

      assertThrows<EmailExistsException> { service.inviteFunder(fundingEntityId, email) }
    }

    @Test
    fun `inviteFunder resends event when user has not yet registered`() {
      val email = "email_${UUID.randomUUID()}@example.com"
      val funderId = insertUser(email = email, authId = null, type = UserType.Funder)
      val fundingEntityId = insertFundingEntity()
      insertFundingEntityUser(fundingEntityId, funderId)

      service.inviteFunder(fundingEntityId, email)

      publisher.assertExactEventsPublished(
          setOf(FunderInvitedToFundingEntityEvent(email = email, fundingEntityId = fundingEntityId))
      )
    }

    @Test
    fun `inviteFunder creates the funder, adds to entity, and sends event`() {
      val email = "email_${UUID.randomUUID()}@example.com"
      val fundingEntityId = insertFundingEntity()

      service.inviteFunder(fundingEntityId, email)

      val userRecord = dslContext.selectFrom(USERS).where(USERS.EMAIL.eq(email)).fetchOne()!!

      assertNull(userRecord.authId, "New Funder Auth ID should be null")
      assertEquals(userRecord.email, email)
      assertEquals(userRecord.userTypeId, UserType.Funder)
      assertTableEquals(
          FundingEntityUsersRecord(fundingEntityId = fundingEntityId, userId = userRecord.id)
      )

      publisher.assertExactEventsPublished(
          setOf(FunderInvitedToFundingEntityEvent(email = email, fundingEntityId = fundingEntityId))
      )
    }
  }
}
