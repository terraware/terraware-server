package com.terraformation.backend.customer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.ProjectInternalUserModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.tables.records.ProjectInternalUsersRecord
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.BagStore
import com.terraformation.backend.seedbank.db.GeolocationStore
import com.terraformation.backend.seedbank.db.ViabilityTestStore
import com.terraformation.backend.seedbank.db.WithdrawalStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException

class ProjectServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock = TestClock()
  private val identifierGenerator: IdentifierGenerator by lazy {
    IdentifierGenerator(clock, dslContext)
  }
  private val messages = Messages()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val publisher = TestEventPublisher()

  private val service: ProjectService by lazy {
    ProjectService(
        AccessionStore(
            dslContext,
            BagStore(dslContext),
            facilitiesDao,
            GeolocationStore(dslContext, clock),
            ViabilityTestStore(dslContext),
            parentStore,
            WithdrawalStore(dslContext, clock, messages, parentStore),
            clock,
            publisher,
            messages,
            identifierGenerator,
        ),
        BatchStore(
            batchDetailsHistoryDao,
            batchDetailsHistorySubLocationsDao,
            batchesDao,
            batchQuantityHistoryDao,
            batchWithdrawalsDao,
            clock,
            dslContext,
            publisher,
            facilitiesDao,
            identifierGenerator,
            parentStore,
            projectsDao,
            subLocationsDao,
            nurseryWithdrawalsDao,
        ),
        dslContext,
        PlantingSiteStore(
            clock,
            TestSingletons.countryDetector,
            dslContext,
            publisher,
            IdentifierGenerator(clock, dslContext),
            monitoringPlotsDao,
            parentStore,
            plantingSeasonsDao,
            plantingSitesDao,
            substrataDao,
            strataDao,
            publisher,
        ),
        ProjectStore(clock, dslContext, publisher, parentStore, projectsDao),
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            InMemoryKeycloakAdminClient(),
            dummyKeycloakInfo(),
            mockk(),
            parentStore,
            PermissionStore(dslContext),
            publisher,
            usersDao,
        ),
    )
  }

  private val accessionId1 by lazy { insertAccession() }
  private val accessionId2 by lazy { insertAccession() }
  private val batchId1 by lazy {
    insertBatch(facilityId = nurseryFacilityId, speciesId = speciesId)
  }
  private val batchId2 by lazy {
    insertBatch(facilityId = nurseryFacilityId, speciesId = speciesId)
  }
  private val projectId by lazy { insertProject() }
  private val nurseryFacilityId by lazy { insertFacility(type = FacilityType.Nursery) }
  private val plantingSiteId1 by lazy { insertPlantingSite() }
  private val plantingSiteId2 by lazy { insertPlantingSite() }
  private val speciesId by lazy { insertSpecies() }

  private val otherOrganizationId by lazy { insertOrganization() }
  private val otherOrgAccessionId by lazy {
    insertAccession(facilityId = otherOrgSeedBankFacilityId)
  }
  private val otherOrgBatchId by lazy {
    insertBatch(
        facilityId = otherOrgNurseryFacilityId,
        organizationId = otherOrganizationId,
        speciesId = otherOrgSpeciesId,
    )
  }
  private val otherOrgNurseryFacilityId by lazy {
    insertFacility(type = FacilityType.Nursery, organizationId = otherOrganizationId)
  }
  private val otherOrgSeedBankFacilityId by lazy {
    insertFacility(organizationId = otherOrganizationId)
  }
  private val otherOrgPlantingSiteId by lazy {
    insertPlantingSite(organizationId = otherOrganizationId)
  }
  private val otherOrgSpeciesId by lazy { insertSpecies(organizationId = otherOrganizationId) }

  @BeforeEach
  fun setUp() {
    every { user.canReadAccession(any()) } returns true
    every { user.canReadBatch(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateAccessionProject(any()) } returns true
    every { user.canUpdateBatch(any()) } returns true
    every { user.canUpdatePlantingSiteProject(any()) } returns true

    insertOrganization()
    insertFacility()
  }

  @Nested
  inner class AssignProject {
    @Test
    fun `updates projects on requested objects`() {
      val nonSelectedAccessionId = insertAccession()
      val nonSelectedBatchId = insertBatch(facilityId = nurseryFacilityId, speciesId = speciesId)
      val nonSelectedPlantingSiteId = insertPlantingSite()

      service.assignProject(
          projectId,
          listOf(accessionId1, accessionId2),
          listOf(batchId1, batchId2),
          listOf(plantingSiteId1, plantingSiteId2),
      )

      assertSetEquals(
          setOf(
              accessionId1 to projectId,
              accessionId2 to projectId,
              nonSelectedAccessionId to null,
          ),
          accessionsDao.findAll().map { it.id to it.projectId }.toSet(),
          "Accession projects",
      )

      assertSetEquals(
          setOf(
              batchId1 to projectId,
              batchId2 to projectId,
              nonSelectedBatchId to null,
          ),
          batchesDao.findAll().map { it.id to it.projectId }.toSet(),
          "Batch projects",
      )

      assertSetEquals(
          setOf(
              plantingSiteId1 to projectId,
              plantingSiteId2 to projectId,
              nonSelectedPlantingSiteId to null,
          ),
          plantingSitesDao.findAll().map { it.id to it.projectId }.toSet(),
          "Planting site projects",
      )
    }

    @Test
    fun `throws exception if no permission to read project`() {
      every { user.canReadProject(any()) } returns false

      assertThrows<ProjectNotFoundException> {
        service.assignProject(projectId, emptyList(), emptyList(), emptyList())
      }
    }

    @Test
    fun `throws exception if no permission to update accession project`() {
      every { user.canUpdateAccessionProject(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.assignProject(projectId, listOf(accessionId1), emptyList(), emptyList())
      }
    }

    @Test
    fun `throws exception if accession and project are from different organizations`() {
      assertThrows<ProjectInDifferentOrganizationException> {
        service.assignProject(
            projectId,
            listOf(accessionId1, otherOrgAccessionId),
            emptyList(),
            emptyList(),
        )
      }
    }

    @Test
    fun `throws exception if no permission to update batch`() {
      every { user.canUpdateBatch(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.assignProject(projectId, emptyList(), listOf(batchId1), emptyList())
      }
    }

    @Test
    fun `throws exception if batch and project are from different organizations`() {
      assertThrows<ProjectInDifferentOrganizationException> {
        service.assignProject(
            projectId,
            emptyList(),
            listOf(batchId1, otherOrgBatchId),
            emptyList(),
        )
      }
    }

    @Test
    fun `throws exception if no permission to update planting site project`() {
      every { user.canUpdatePlantingSiteProject(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.assignProject(projectId, emptyList(), emptyList(), listOf(plantingSiteId1))
      }
    }

    @Test
    fun `throws exception if planting site and project are from different organizations`() {
      assertThrows<ProjectInDifferentOrganizationException> {
        service.assignProject(
            projectId,
            emptyList(),
            emptyList(),
            listOf(plantingSiteId1, otherOrgPlantingSiteId),
        )
      }
    }
  }

  @Nested
  inner class UpdateInternalUsers {
    @Test
    fun `throws exception if user has no globalRoles`() {
      assertThrows<IllegalStateException> {
        service.updateInternalUsers(
            projectId,
            listOf(ProjectInternalUserModel(user.userId, ProjectInternalRole.RegionalExpert)),
        )
      }
    }

    @Test
    fun `happy path`() {
      every { user.canUpdateProjectInternalUsers(any()) } returns true
      val userToAdd = insertUser()
      insertUserGlobalRole(userId = userToAdd, role = GlobalRole.ReadOnly)
      val userToRemove = insertUser()
      insertUserGlobalRole(userId = userToRemove, role = GlobalRole.AcceleratorAdmin)
      insertProjectInternalUser(
          projectId = projectId,
          userId = userToRemove,
          role = ProjectInternalRole.ClimateImpactLead,
      )
      val userToKeep = insertUser()
      insertUserGlobalRole(userId = userToKeep, role = GlobalRole.TFExpert)
      insertProjectInternalUser(
          userId = userToKeep,
          role = ProjectInternalRole.ProjectFinanceLead,
      )
      val userToChange = insertUser()
      insertUserGlobalRole(userId = userToChange, role = GlobalRole.SuperAdmin)
      insertProjectInternalUser(
          userId = userToChange,
          roleName = "Some Role",
      )

      service.updateInternalUsers(
          projectId,
          listOf(
              ProjectInternalUserModel(userToAdd, roleName = "A new role"),
              ProjectInternalUserModel(userToKeep, ProjectInternalRole.ProjectFinanceLead),
              ProjectInternalUserModel(userToChange, ProjectInternalRole.LegalLead),
          ),
      )

      assertTableEquals(
          listOf(
              ProjectInternalUsersRecord(
                  projectId = projectId,
                  userId = userToAdd,
                  roleName = "A new role",
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectInternalUsersRecord(
                  projectId = projectId,
                  userId = userToKeep,
                  projectInternalRoleId = ProjectInternalRole.ProjectFinanceLead,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectInternalUsersRecord(
                  projectId = projectId,
                  userId = userToChange,
                  projectInternalRoleId = ProjectInternalRole.LegalLead,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          )
      )
    }
  }
}
