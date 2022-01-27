package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.FacilityAlertRecipientsDao
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.ProjectTypeSelectionsDao
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.i18n.Messages
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired

internal class OrganizationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: UserModel = mockk()
  override val sequencesToReset: List<String> =
      listOf("organizations_id_seq", "projects_id_seq", "site_id_seq", "site_module_id_seq")

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock: Clock = mockk()
  private val emailService: EmailService = mockk()
  private lateinit var facilityStore: FacilityStore
  private val messages: Messages = mockk()
  private lateinit var organizationStore: OrganizationStore
  private lateinit var projectStore: ProjectStore
  private val realmResource: RealmResource = mockk()
  private lateinit var siteStore: SiteStore
  private lateinit var userStore: UserStore

  private lateinit var service: OrganizationService

  private val seedBankDefaultName = "Seed Bank"

  @BeforeEach
  fun setUp() {
    val jooqConfig = dslContext.configuration()

    every { realmResource.users() } returns mockk()

    facilityStore =
        FacilityStore(
            clock, dslContext, FacilitiesDao(jooqConfig), FacilityAlertRecipientsDao(jooqConfig))
    organizationStore = OrganizationStore(clock, dslContext, OrganizationsDao(jooqConfig))
    projectStore =
        ProjectStore(
            clock, dslContext, ProjectsDao(jooqConfig), ProjectTypeSelectionsDao(jooqConfig))
    siteStore = SiteStore(clock, dslContext, SitesDao(jooqConfig))
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            mockk(),
            jacksonObjectMapper(),
            organizationStore,
            ParentStore(dslContext),
            PermissionStore(dslContext),
            realmResource,
            UsersDao(jooqConfig))

    service =
        OrganizationService(
            dslContext,
            emailService,
            facilityStore,
            messages,
            organizationStore,
            projectStore,
            siteStore,
            userStore)

    every { clock.instant() } returns Instant.EPOCH
    every { messages.seedBankDefaultName() } returns seedBankDefaultName
    every { user.canCreateFacility(any()) } returns true
    every { user.canCreateProject(any()) } returns true
    every { user.canCreateSite(any()) } returns true
    every { user.userId } returns UserId(100)
  }

  @Test
  fun `createOrganization creates seed bank`() {
    insertUser(user.userId)

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
                                                id = FacilityId(1),
                                                modifiedTime = clock.instant(),
                                                name = seedBankDefaultName,
                                                siteId = SiteId(1),
                                                type = FacilityType.SeedBank)))))))
    val actual =
        service.createOrganization(
            OrganizationsRow(name = "Test Organization"), createSeedBank = true)

    assertEquals(expected, actual)
  }

  @Test
  fun `createOrganization does not create seed bank if creation flag is false`() {
    insertUser(user.userId)

    val expected =
        OrganizationModel(
            createdTime = clock.instant(),
            id = OrganizationId(1),
            name = "Test Organization",
            projects = null)
    val actual =
        service.createOrganization(
            OrganizationsRow(name = "Test Organization"), createSeedBank = false)

    assertEquals(expected, actual)
  }
}
