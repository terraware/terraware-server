package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationDeletedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.records.OrganizationUsersRecord
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException

internal class OrganizationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ORGANIZATIONS, PROJECTS, SITES, FACILITIES)

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock: Clock = mockk()
  private lateinit var facilityStore: FacilityStore
  private val messages: Messages = mockk()
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var projectStore: ProjectStore
  private val publisher: ApplicationEventPublisher = mockk()
  private val realmResource: RealmResource = mockk()
  private lateinit var siteStore: SiteStore
  private lateinit var userStore: UserStore
  private lateinit var notificationStore: NotificationStore

  private lateinit var service: OrganizationService

  private val seedBankDefaultName = "Seed Bank"

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    facilityStore = FacilityStore(clock, dslContext, facilitiesDao, storageLocationsDao)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    parentStore = ParentStore(dslContext)
    projectStore = ProjectStore(clock, dslContext, projectsDao, projectTypeSelectionsDao)
    siteStore = SiteStore(clock, dslContext, parentStore, sitesDao)
    notificationStore = NotificationStore(dslContext, clock)
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            mockk(),
            jacksonObjectMapper(),
            organizationStore,
            parentStore,
            PermissionStore(dslContext),
            realmResource,
            usersDao,
            notificationStore,
        )

    service =
        OrganizationService(
            dslContext,
            facilityStore,
            messages,
            organizationStore,
            projectStore,
            publisher,
            siteStore,
            userStore)

    every { clock.instant() } returns Instant.EPOCH
    every { messages.seedBankDefaultName() } returns seedBankDefaultName
    every { user.canCreateFacility(any()) } returns true
    every { user.canCreateProject(any()) } returns true
    every { user.canCreateSite(any()) } returns true
    every { user.canCreateStorageLocation(any()) } returns true
    every { user.canDeleteOrganization(any()) } returns true
    every { user.canListOrganizationUsers(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadStorageLocation(any()) } returns true
    every { user.canRemoveOrganizationUser(any(), any()) } returns true
  }

  @Test
  fun `createOrganization creates seed bank`() {
    insertUser()

    val expected =
        OrganizationModel(
            createdTime = clock.instant(),
            id = OrganizationId(1),
            name = "Test Organization",
            projects =
                listOf(
                    ProjectModel(
                        createdTime = clock.instant(),
                        hidden = true,
                        id = ProjectId(1),
                        organizationId = OrganizationId(1),
                        organizationWide = true,
                        name = seedBankDefaultName,
                        description = null,
                        startDate = null,
                        status = null,
                        sites =
                            listOf(
                                SiteModel(
                                    createdTime = clock.instant(),
                                    description = null,
                                    id = SiteId(1),
                                    location = null,
                                    modifiedTime = clock.instant(),
                                    name = seedBankDefaultName,
                                    projectId = ProjectId(1),
                                    facilities =
                                        listOf(
                                            FacilityModel(
                                                createdTime = clock.instant(),
                                                description = null,
                                                id = FacilityId(1),
                                                lastTimeseriesTime = null,
                                                maxIdleMinutes = 30,
                                                modifiedTime = clock.instant(),
                                                name = seedBankDefaultName,
                                                siteId = SiteId(1),
                                                type = FacilityType.SeedBank)))))),
            totalUsers = 1)
    val actual =
        service.createOrganization(
            OrganizationsRow(name = "Test Organization"), createSeedBank = true)

    assertEquals(expected, actual)

    val storageLocations = facilityStore.fetchStorageLocations(FacilityId(1))

    assertEquals(
        3,
        storageLocations.count {
          it.conditionId == StorageCondition.Refrigerator &&
              it.name?.startsWith("Refrigerator") == true
        },
        "Number of refrigerators")
    assertEquals(
        3,
        storageLocations.count {
          it.conditionId == StorageCondition.Freezer && it.name?.startsWith("Freezer") == true
        },
        "Number of freezers")
  }

  @Test
  fun `createOrganization does not create seed bank if creation flag is false`() {
    insertUser()

    val expected =
        OrganizationModel(
            createdTime = clock.instant(),
            id = OrganizationId(1),
            name = "Test Organization",
            projects = null,
            totalUsers = 1)
    val actual =
        service.createOrganization(
            OrganizationsRow(name = "Test Organization"), createSeedBank = false)

    assertEquals(expected, actual)
  }

  @Test
  fun `deleteOrganization throws exception if organization has users other than the current one`() {
    val organizationId = OrganizationId(1)
    val otherUserId = UserId(100)

    insertUser(user.userId)
    insertUser(otherUserId)
    insertOrganization(organizationId)
    insertOrganizationUser(user.userId, organizationId, Role.OWNER)
    insertOrganizationUser(otherUserId, organizationId, Role.CONTRIBUTOR)

    assertThrows<OrganizationHasOtherUsersException> { service.deleteOrganization(organizationId) }
  }

  @Test
  fun `deleteOrganization throws exception if user has no permission to delete organization`() {
    val organizationId = OrganizationId(1)
    every { user.canDeleteOrganization(organizationId) } returns false

    assertThrows<AccessDeniedException> { service.deleteOrganization(organizationId) }
  }

  @Test
  fun `deleteOrganization removes current user from organization`() {
    val organizationId = OrganizationId(1)

    insertUser()
    insertOrganization(organizationId)
    insertOrganizationUser(user.userId, organizationId, Role.OWNER)

    every { publisher.publishEvent(any<OrganizationDeletedEvent>()) } just Runs

    service.deleteOrganization(organizationId)

    val expected = emptyList<OrganizationUsersRecord>()
    val actual = dslContext.selectFrom(ORGANIZATION_USERS).fetch()
    assertEquals(expected, actual)
  }

  @Test
  fun `deleteOrganization publishes event on success`() {
    val organizationId = OrganizationId(1)

    insertUser()
    insertOrganization(organizationId)
    insertOrganizationUser(user.userId, organizationId, Role.OWNER)

    every { publisher.publishEvent(any<OrganizationDeletedEvent>()) } just Runs

    service.deleteOrganization(organizationId)

    verify { publisher.publishEvent(OrganizationDeletedEvent(organizationId)) }
  }
}
