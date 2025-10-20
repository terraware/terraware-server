package com.terraformation.backend.report

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.SeedFundReportAlreadySubmittedException
import com.terraformation.backend.db.SeedFundReportLockedException
import com.terraformation.backend.db.SeedFundReportNotLockedException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.report.model.SeedFundReportBodyModelV1
import com.terraformation.backend.report.model.SeedFundReportMetadata
import com.terraformation.backend.report.model.SeedFundReportModel
import com.terraformation.backend.report.render.SeedFundReportRenderer
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SeedFundReportServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val googleDriveWriter: GoogleDriveWriter = mockk()
  private val messages = Messages()
  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val publisher = TestEventPublisher()
  private val parentStore by lazy { ParentStore(dslContext) }
  private val seedFundReportRenderer: SeedFundReportRenderer = mockk()
  private val seedFundReportStore by lazy {
    SeedFundReportStore(
        clock,
        dslContext,
        publisher,
        facilitiesDao,
        objectMapper,
        parentStore,
        projectsDao,
        seedFundReportsDao,
    )
  }
  private val scheduler: JobScheduler = mockk()

  private val service by lazy {
    SeedFundReportService(
        AccessionStore(
            dslContext,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            parentStore,
            mockk(),
            clock,
            publisher,
            messages,
            mockk(),
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
            mockk(),
            parentStore,
            projectsDao,
            subLocationsDao,
            nurseryWithdrawalsDao,
        ),
        clock,
        mockk(),
        FacilityStore(
            clock,
            mockk(),
            dslContext,
            publisher,
            facilitiesDao,
            messages,
            organizationsDao,
            subLocationsDao,
        ),
        googleDriveWriter,
        OrganizationStore(clock, dslContext, organizationsDao, publisher),
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
            plantingSubzonesDao,
            plantingZonesDao,
            publisher,
        ),
        ProjectStore(clock, dslContext, publisher, parentStore, projectsDao),
        seedFundReportRenderer,
        seedFundReportStore,
        scheduler,
        SpeciesStore(
            clock,
            dslContext,
            speciesDao,
            speciesEcosystemTypesDao,
            speciesGrowthFormsDao,
            speciesProblemsDao,
        ),
        SystemUser(usersDao),
    )
  }

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

    every { user.canCreateSeedFundReport(any()) } returns true
    every { user.canDeleteSeedFundReport(any()) } returns true
    every { user.canListSeedFundReports(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSeedFundReport(any()) } returns true
    every { user.canUpdateSeedFundReport(any()) } returns true
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)

    insertOrganizationUser(user.userId, organizationId, Role.Admin)
  }

  @Nested
  inner class Create {
    @Test
    fun `populates all server-generated fields`() {
      val speciesId =
          insertSpecies(growthForms = setOf(GrowthForm.Shrub), scientificName = "My species")

      val nurseryId =
          insertFacility(
              buildCompletedDate = LocalDate.of(2023, 3, 1),
              buildStartedDate = LocalDate.of(2023, 2, 1),
              capacity = 1000,
              name = "Nursery",
              type = FacilityType.Nursery,
          )

      val seedBankId =
          insertFacility(
              name = "Seed Bank",
              operationStartedDate = LocalDate.of(2023, 4, 1),
              type = FacilityType.SeedBank,
          )
      insertAccession(
          AccessionsRow(
              facilityId = seedBankId,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )

      val plantingSiteId = insertPlantingSite()

      insertSampleWithdrawals(speciesId, nurseryId, plantingSiteId)

      val created = service.create(organizationId)

      val expected =
          SeedFundReportModel(
              SeedFundReportBodyModelV1(
                  annualDetails = SeedFundReportBodyModelV1.AnnualDetails(),
                  isAnnual = true,
                  nurseries =
                      listOf(
                          SeedFundReportBodyModelV1.Nursery(
                              buildCompletedDate = LocalDate.of(2023, 3, 1),
                              buildCompletedDateEditable = false,
                              buildStartedDate = LocalDate.of(2023, 2, 1),
                              buildStartedDateEditable = false,
                              capacity = 1000,
                              id = nurseryId,
                              // 152 dead / (898 remaining + 242 total withdrawn) = 13.3%
                              mortalityRate = 13,
                              name = "Nursery",
                              // inventory (200 active-growth, 300 ready, 400 hardening-off) +
                              // outplanting withdrawals (20 active-growth, 30 ready, 40
                              // hardening-off)
                              totalPlantsPropagated = 990,
                          ),
                      ),
                  organizationName = "Organization 1",
                  plantingSites =
                      listOf(
                          SeedFundReportBodyModelV1.PlantingSite(
                              id = plantingSiteId,
                              name = "Site 1",
                              species =
                                  listOf(
                                      SeedFundReportBodyModelV1.PlantingSite.Species(
                                          growthForms = setOf(GrowthForm.Shrub),
                                          id = speciesId,
                                          scientificName = "My species",
                                      ),
                                  ),
                          ),
                      ),
                  seedBanks =
                      listOf(
                          SeedFundReportBodyModelV1.SeedBank(
                              id = seedBankId,
                              name = "Seed Bank",
                              operationStartedDate = LocalDate.of(2023, 4, 1),
                              operationStartedDateEditable = false,
                              totalSeedsStored = 10,
                          ),
                      ),
                  totalNurseries = 1,
                  totalPlantingSites = 1,
                  totalSeedBanks = 1,
              ),
              SeedFundReportMetadata(
                  created.id,
                  organizationId = organizationId,
                  quarter = 4,
                  status = SeedFundReportStatus.New,
                  year = 1969,
              ),
          )

      val actual = seedFundReportStore.fetchOneById(created.id)

      assertJsonEquals(expected, actual)
    }

    @Test
    fun `only includes project-related values in project-level report bodies`() {
      val projectId = insertProject(name = "Test Project")
      val otherProjectId = insertProject(name = "Other Project")
      val speciesId =
          insertSpecies(growthForms = setOf(GrowthForm.Shrub), scientificName = "My species")

      val projectNurseryId =
          insertFacility(
              buildCompletedDate = LocalDate.of(2023, 3, 1),
              buildStartedDate = LocalDate.of(2023, 2, 1),
              capacity = 1000,
              type = FacilityType.Nursery,
          )

      val projectSeedBankId =
          insertFacility(
              operationStartedDate = LocalDate.of(2023, 4, 1),
              type = FacilityType.SeedBank,
          )

      val nonProjectNurseryId = insertFacility(type = FacilityType.Nursery)
      val nonProjectSeedBankId = insertFacility(type = FacilityType.SeedBank)
      val nonProjectPlantingSiteId = insertPlantingSite()
      val otherProjectPlantingSiteId = insertPlantingSite(projectId = otherProjectId)

      insertAccession(
          AccessionsRow(
              facilityId = projectSeedBankId,
              projectId = projectId,
              remainingQuantity = BigDecimal(1),
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )
      insertAccession(
          AccessionsRow(
              facilityId = projectSeedBankId,
              remainingQuantity = BigDecimal(2),
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )
      insertAccession(
          AccessionsRow(
              facilityId = projectSeedBankId,
              projectId = otherProjectId,
              remainingQuantity = BigDecimal(4),
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )
      insertAccession(
          AccessionsRow(
              facilityId = nonProjectSeedBankId,
              projectId = otherProjectId,
              remainingQuantity = BigDecimal(8),
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )
      insertAccession(
          AccessionsRow(
              facilityId = nonProjectSeedBankId,
              projectId = projectId,
              remainingQuantity = BigDecimal(16),
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )

      val projectPlantingSiteId = insertPlantingSite(projectId = projectId)

      // This sample will be counted toward the project: the batches are tagged with the project ID.
      insertSampleWithdrawals(speciesId, projectNurseryId, projectPlantingSiteId, projectId)

      // These samples won't count toward totalPlantsPropagatedForProject but because they are at a
      // nursery that has batches for the project, they will count toward totalPlantsPropagated.
      insertSampleWithdrawals(speciesId, projectNurseryId, nonProjectPlantingSiteId)
      insertSampleWithdrawals(
          speciesId,
          projectNurseryId,
          otherProjectPlantingSiteId,
          otherProjectId,
      )

      // This sample is at a different nursery which won't appear in the per-project report because
      // it has no batches for the project in question.
      insertSampleWithdrawals(speciesId, nonProjectNurseryId, projectPlantingSiteId)

      val created = service.create(organizationId, projectId)

      val expected =
          SeedFundReportModel(
              SeedFundReportBodyModelV1(
                  annualDetails = SeedFundReportBodyModelV1.AnnualDetails(),
                  isAnnual = true,
                  nurseries =
                      listOf(
                          SeedFundReportBodyModelV1.Nursery(
                              buildCompletedDate = LocalDate.of(2023, 3, 1),
                              buildCompletedDateEditable = false,
                              buildStartedDate = LocalDate.of(2023, 2, 1),
                              buildStartedDateEditable = false,
                              capacity = 1000,
                              id = projectNurseryId,
                              // 152 dead / (898 remaining + 242 total withdrawn) = 13.3%
                              mortalityRate = 13,
                              name = "Facility 1",
                              // Project-level total only counts one of the four samples:
                              //   inventory (200 active-growth, 300 ready, 400 hardening-off) +
                              //   outplanting withdrawals (20 active-growth, 30 ready, 40
                              // hardening-off)
                              totalPlantsPropagatedForProject = 990,
                              // Org-level total counts all three samples for this nursery (same
                              // numbers as above for each sample).
                              totalPlantsPropagated = 2970,
                          ),
                      ),
                  organizationName = "Organization 1",
                  plantingSites =
                      listOf(
                          SeedFundReportBodyModelV1.PlantingSite(
                              id = projectPlantingSiteId,
                              name = "Site 3",
                              species =
                                  listOf(
                                      SeedFundReportBodyModelV1.PlantingSite.Species(
                                          growthForms = setOf(GrowthForm.Shrub),
                                          id = speciesId,
                                          scientificName = "My species",
                                      ),
                                  ),
                          ),
                      ),
                  seedBanks =
                      listOf(
                          SeedFundReportBodyModelV1.SeedBank(
                              id = projectSeedBankId,
                              name = "Facility 2",
                              operationStartedDate = LocalDate.of(2023, 4, 1),
                              operationStartedDateEditable = false,
                              totalSeedsStored = 7,
                              totalSeedsStoredForProject = 1,
                          ),
                          SeedFundReportBodyModelV1.SeedBank(
                              id = nonProjectSeedBankId,
                              name = "Facility 4",
                              operationStartedDateEditable = true,
                              totalSeedsStored = 24,
                              totalSeedsStoredForProject = 16,
                          ),
                      ),
                  totalNurseries = 1,
                  totalPlantingSites = 1,
                  totalSeedBanks = 2,
              ),
              SeedFundReportMetadata(
                  created.id,
                  organizationId = organizationId,
                  projectId = projectId,
                  projectName = "Test Project",
                  quarter = 4,
                  status = SeedFundReportStatus.New,
                  year = 1969,
              ),
          )

      val actual = seedFundReportStore.fetchOneById(created.id)

      assertJsonEquals(expected, actual)
    }

    @Test
    fun `does not create annual report for mid-year quarters`() {
      clock.instant = ZonedDateTime.of(2022, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      val created = service.create(organizationId)

      val body = seedFundReportStore.fetchOneById(created.id).body.toLatestVersion()

      assertFalse(body.isAnnual, "Is annual")
      assertNull(body.annualDetails, "Annual details")
    }

    @Test
    fun `creates annual report in December`() {
      clock.instant = ZonedDateTime.of(2022, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      val created = service.create(organizationId)

      val body = seedFundReportStore.fetchOneById(created.id).body.toLatestVersion()

      assertTrue(body.isAnnual, "Is annual")
      assertNotNull(body.annualDetails, "Annual details")
    }
  }

  @Nested
  inner class CreateMissingReports {
    @Test
    fun `listens for event`() {
      assertIsEventListener<DailyTaskTimeArrivedEvent>(service, "createMissingReports")
    }

    @Test
    fun `creates reports for organizations that need them`() {
      insertOrganization()
      val alreadyInProgressOrganization = insertOrganization()

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertOrganizationInternalTag(alreadyInProgressOrganization, InternalTagIds.Reporter)
      insertSeedFundReport(organizationId = alreadyInProgressOrganization, quarter = 4, year = 1969)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertNotNull(seedFundReportStore.fetchMetadataByOrganization(organizationId).firstOrNull())
    }

    @Test
    fun `does not create reports for organizations that have org-level reports disabled`() {
      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertOrganizationReportSettings(isEnabled = false)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          emptyList<Any>(),
          seedFundReportStore.fetchMetadataByOrganization(organizationId),
      )
    }

    @Test
    fun `creates reports for projects that need them`() {
      val projectWithOlderReport = insertProject()
      val reportsEnabledProject = insertProject()

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertProjectReportSettings(projectId = projectWithOlderReport)
      insertProjectReportSettings(projectId = reportsEnabledProject, isEnabled = true)

      insertSeedFundReport(projectId = projectWithOlderReport, quarter = 3, year = 1969)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          1,
          seedFundReportsDao.fetchByProjectId(reportsEnabledProject).size,
          "Should have created report for project with no existing reports and reports enabled",
      )
      assertEquals(
          2,
          seedFundReportsDao.fetchByProjectId(projectWithOlderReport).size,
          "Should have created current-quarter report for project with older report",
      )
    }

    @Test
    fun `creates reports for projects with no report settings`() {
      val projectId = insertProject()

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          1,
          seedFundReportsDao.fetchByProjectId(projectId).size,
          "Number of reports for project with no existing reports and no settings",
      )
    }

    @Test
    fun `does not creates reports for projects that have them disabled`() {
      val projectId = insertProject()

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertOrganizationReportSettings(isEnabled = false)
      insertProjectReportSettings(projectId = projectId, isEnabled = false)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          emptyList<Any>(),
          seedFundReportsDao.fetchByProjectId(projectId),
          "Should not have created report for project with reports disabled",
      )
    }

    @Test
    fun `does not create reports for projects that already have them`() {
      val projectId = insertProject()
      val reportId = insertSeedFundReport(projectId = projectId, quarter = 4, year = 1969)

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertProjectReportSettings(projectId = projectId, isEnabled = true)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          listOf(reportId),
          seedFundReportsDao.fetchByProjectId(projectId).map { it.id },
          "Should not have created additional report when one was already in progress",
      )
    }

    @Test
    fun `does not create reports for projects whose organizations are not tagged as reporters`() {
      val projectId = insertProject()

      insertOrganizationReportSettings(organizationId, isEnabled = true)
      insertProjectReportSettings(projectId, isEnabled = true)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertTableEmpty(SEED_FUND_REPORTS)
    }
  }

  @Nested
  inner class DeleteOrganization {
    @Test
    fun `deletes all reports for organization when organization deletion starts`() {
      insertSeedFundReport(year = 2000)
      insertSeedFundReport(year = 2001)
      val otherOrganizationId = insertOrganization()
      val otherOrgReportId = insertSeedFundReport(organizationId = otherOrganizationId)

      service.on(OrganizationDeletionStartedEvent(organizationId))

      assertEquals(
          listOf(otherOrgReportId),
          seedFundReportsDao.findAll().map { it.id },
          "Report IDs",
      )

      assertIsEventListener<OrganizationDeletionStartedEvent>(service)
    }
  }

  @Nested
  inner class DeleteProject {
    @Test
    fun `deletes unsubmitted project-level reports when project is deleted`() {
      val deletedProjectId = insertProject()
      val keptProjectId = insertProject()
      val orgLevelReportId = insertSeedFundReport()
      val keptProjectReportId = insertSeedFundReport(projectId = keptProjectId)
      val deletedProjectReportId = insertSeedFundReport(projectId = deletedProjectId)

      service.on(ProjectDeletionStartedEvent(deletedProjectId))

      projectsDao.deleteById(deletedProjectId)

      assertFalse(
          seedFundReportsDao.existsById(deletedProjectReportId),
          "Should have deleted report for deleted project",
      )
      assertTrue(
          seedFundReportsDao.existsById(keptProjectReportId),
          "Should not have deleted report for non-deleted project",
      )
      assertTrue(
          seedFundReportsDao.existsById(orgLevelReportId),
          "Should not have deleted org-level report",
      )
    }

    @Test
    fun `keeps submitted project-level reports for deleted projects`() {
      val projectId = insertProject(name = "Test Project")
      val orgLevelReportId = insertSeedFundReport(year = 1990)
      val submittedReportId =
          insertSeedFundReport(projectId = projectId, year = 1990, submittedBy = user.userId)

      service.on(ProjectDeletionStartedEvent(projectId))

      projectsDao.deleteById(projectId)

      val reportsRow = seedFundReportsDao.fetchOneById(submittedReportId)
      assertNotNull(reportsRow, "Should not have deleted submitted report")
      assertNull(reportsRow?.projectId, "Should have cleared project ID from submitted report")
      assertEquals(
          "Test Project",
          reportsRow?.projectName,
          "Should have kept project name on submitted report",
      )
      assertNotNull(
          seedFundReportsDao.fetchOneById(orgLevelReportId),
          "Should not have deleted org-level report",
      )
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<ProjectDeletionStartedEvent>(service)
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `refreshes server-generated fields if report is not submitted`() {
      val firstSeedBank =
          insertFacility(
              type = FacilityType.SeedBank,
              buildCompletedDate = LocalDate.of(2023, 2, 2),
          )
      val firstNursery = insertFacility(type = FacilityType.Nursery)
      val firstPlantingSite = insertPlantingSite()

      insertAccession(
          AccessionsRow(
              facilityId = firstSeedBank,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )

      val speciesId =
          insertSpecies(growthForms = setOf(GrowthForm.Forb), scientificName = "New species")
      insertBatch(
          facilityId = firstNursery,
          germinatingQuantity = 50,
          activeGrowthQuantity = 60,
          readyQuantity = 70,
          hardeningOffQuantity = 80,
          speciesId = speciesId,
      )
      val withdrawalId = insertNurseryWithdrawal(facilityId = firstNursery)
      val deliveryId =
          insertDelivery(plantingSiteId = firstPlantingSite, withdrawalId = withdrawalId)
      insertPlanting(deliveryId = deliveryId, speciesId = speciesId)

      val initialBody =
          SeedFundReportBodyModelV1(
              annualDetails =
                  SeedFundReportBodyModelV1.AnnualDetails(
                      bestMonthsForObservation = setOf(1, 2, 3),
                      budgetNarrativeSummary = "budget narrative",
                      catalyticDetail = "catalytic detail",
                      challenges = "challenges",
                      isCatalytic = true,
                      keyLessons = "key lessons",
                      nextSteps = "next steps",
                      projectImpact = "project impact",
                      projectSummary = "project summary",
                      socialImpact = "social impact",
                      successStories = "success stories",
                      sustainableDevelopmentGoals =
                          listOf(
                              SeedFundReportBodyModelV1.AnnualDetails.GoalProgress(
                                  SustainableDevelopmentGoal.CleanWater,
                                  "clean water progress",
                              ),
                          ),
                  ),
              isAnnual = true,
              nurseries =
                  listOf(
                      SeedFundReportBodyModelV1.Nursery(
                          buildCompletedDate = LocalDate.of(2023, 1, 2),
                          buildStartedDate = LocalDate.of(2023, 1, 1),
                          capacity = 1,
                          id = firstNursery,
                          mortalityRate = 0,
                          name = "old nursery name",
                          notes = "nursery notes",
                          totalPlantsPropagated = 130,
                          workers = SeedFundReportBodyModelV1.Workers(1, 2, 3),
                      ),
                  ),
              notes = "top-level notes",
              organizationName = "old org name",
              plantingSites =
                  listOf(
                      SeedFundReportBodyModelV1.PlantingSite(
                          id = firstPlantingSite,
                          mortalityRate = 10,
                          name = "old planting site name",
                          selected = false,
                          species =
                              listOf(
                                  SeedFundReportBodyModelV1.PlantingSite.Species(
                                      growthForms = setOf(GrowthForm.Forb),
                                      id = speciesId,
                                      mortalityRateInField = 9,
                                      scientificName = "Old species",
                                      totalPlanted = 99,
                                  ),
                              ),
                          totalPlantedArea = 10,
                          totalPlantingSiteArea = 11,
                          totalPlantsPlanted = 12,
                          totalTreesPlanted = 13,
                          workers = SeedFundReportBodyModelV1.Workers(4, 5, 6),
                      ),
                  ),
              seedBanks =
                  listOf(
                      SeedFundReportBodyModelV1.SeedBank(
                          buildCompletedDate = LocalDate.of(2023, 2, 2),
                          buildCompletedDateEditable = false,
                          buildStartedDate = LocalDate.of(2023, 3, 1),
                          id = firstSeedBank,
                          name = "old seedbank name",
                          notes = "seedbank notes",
                          operationStartedDate = LocalDate.of(2023, 4, 1),
                          totalSeedsStored = 1000L,
                          workers = SeedFundReportBodyModelV1.Workers(7, 8, 9),
                      ),
                  ),
              summaryOfProgress = "summary of progress",
              totalNurseries = 1,
              totalPlantingSites = 1,
              totalSeedBanks = 1,
          )

      val reportId = insertSeedFundReport(body = objectMapper.writeValueAsString(initialBody))
      val initialMetadata = service.fetchOneById(reportId).metadata

      val secondSeedBank =
          insertFacility(
              type = FacilityType.SeedBank,
              buildStartedDate = LocalDate.EPOCH,
          )
      val secondNursery = insertFacility(type = FacilityType.Nursery)
      val secondPlantingSite = insertPlantingSite()
      facilitiesDao.update(
          facilitiesDao
              .fetchOneById(firstNursery)!!
              .copy(buildCompletedDate = LocalDate.of(2023, 1, 15)),
      )
      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(name = "New org name"),
      )

      insertSampleWithdrawals(speciesId, firstNursery, secondPlantingSite)

      val expected =
          SeedFundReportModel(
              body =
                  initialBody.copy(
                      nurseries =
                          listOf(
                              initialBody.nurseries[0].copy(
                                  buildCompletedDate = LocalDate.of(2023, 1, 15),
                                  buildCompletedDateEditable = false,
                                  // 152 dead / (1088 remaining + 242 total withdrawn) = 11.3%
                                  mortalityRate = 11,
                                  name = "Facility 2",
                                  // initial batch (60 active-growth, 70 ready, 80 hardening-off) +
                                  // insertSampleWithdrawals batch (200 active-growth, 300 ready,
                                  // 400 hardening-off) +
                                  // outplanting withdrawals (20 active-growth, 30 ready, 40
                                  // hardening-off)
                                  totalPlantsPropagated = 1200,
                              ),
                              SeedFundReportBodyModelV1.Nursery(
                                  id = secondNursery,
                                  mortalityRate = 0,
                                  name = "Facility 4",
                                  totalPlantsPropagated = 0,
                              ),
                          ),
                      organizationName = "New org name",
                      plantingSites =
                          listOf(
                              initialBody.plantingSites[0].copy(
                                  name = "Site 1",
                                  species =
                                      listOf(
                                          initialBody.plantingSites[0]
                                              .species[0]
                                              .copy(
                                                  scientificName = "New species",
                                              ),
                                      ),
                              ),
                              SeedFundReportBodyModelV1.PlantingSite(
                                  id = secondPlantingSite,
                                  name = "Site 2",
                                  species =
                                      listOf(
                                          SeedFundReportBodyModelV1.PlantingSite.Species(
                                              growthForms = setOf(GrowthForm.Forb),
                                              id = speciesId,
                                              scientificName = "New species",
                                          ),
                                      ),
                              ),
                          ),
                      seedBanks =
                          listOf(
                              initialBody.seedBanks[0].copy(
                                  name = "Facility 1",
                                  totalSeedsStored = 10L,
                              ),
                              SeedFundReportBodyModelV1.SeedBank(
                                  buildStartedDate = LocalDate.EPOCH,
                                  buildStartedDateEditable = false,
                                  id = secondSeedBank,
                                  name = "Facility 3",
                              ),
                          ),
                      totalNurseries = 2,
                      totalPlantingSites = 2,
                      totalSeedBanks = 2,
                  ),
              metadata = initialMetadata,
          )

      val actual = service.fetchOneById(reportId)

      assertJsonEquals(expected, actual)
      assertFalse((actual.body as SeedFundReportBodyModelV1).seedBanks[1].buildStartedDateEditable)
    }

    @Test
    fun `does not refresh server-generated fields if report was already submitted`() {
      val reportId = insertSeedFundReport(submittedBy = user.userId)

      val expected = service.fetchOneById(reportId)

      insertFacility(type = FacilityType.SeedBank)
      insertFacility(type = FacilityType.Nursery)
      insertPlantingSite()
      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(name = "New name"),
      )

      val actual = service.fetchOneById(reportId)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `calls modify function with up-to-date report body`() {
      val reportId = insertSeedFundReport(lockedBy = user.userId)
      val seedBankId = insertFacility()

      val newNotes = "new notes"
      var calledWithSeedBanks: List<SeedFundReportBodyModelV1.SeedBank>? = null

      service.update(reportId) {
        calledWithSeedBanks = it.seedBanks
        it.copy(notes = newNotes)
      }

      assertEquals(
          seedBankId,
          calledWithSeedBanks?.firstOrNull()?.id,
          "Seed bank list should have been refreshed",
      )
      assertEquals(
          newNotes,
          service.fetchOneById(reportId).body.toLatestVersion().notes,
          "Updated data should have been saved",
      )
    }

    @Test
    fun `throws exception if report is not locked`() {
      val reportId = insertSeedFundReport()

      assertThrows<SeedFundReportNotLockedException> { service.update(reportId) { it } }
    }

    @Test
    fun `throws exception if report is locked by another user`() {
      val otherUserId = insertUser()
      val reportId = insertSeedFundReport(lockedBy = otherUserId)

      assertThrows<SeedFundReportLockedException> { service.update(reportId) { it } }
    }

    @Test
    fun `throws exception if report is already submitted`() {
      val reportId = insertSeedFundReport(submittedBy = user.userId)

      assertThrows<SeedFundReportAlreadySubmittedException> { service.update(reportId) { it } }
    }
  }

  @Nested
  inner class UpdateProjectName {
    @Test
    fun `listens for event`() {
      assertIsEventListener<ProjectRenamedEvent>(service)
    }

    @Test
    fun `renames project on unsubmitted reports`() {
      val projectId = insertProject(name = "Old Name")
      val otherProjectId = insertProject(name = "Other")

      val submittedReportId =
          insertSeedFundReport(
              projectId = projectId,
              year = 1990,
              submittedBy = user.userId,
              status = SeedFundReportStatus.Submitted,
          )
      val lockedReportId =
          insertSeedFundReport(
              projectId = projectId,
              year = 1991,
              lockedBy = user.userId,
              status = SeedFundReportStatus.Locked,
          )
      val newReportId =
          insertSeedFundReport(
              projectId = projectId,
              year = 1992,
              status = SeedFundReportStatus.New,
          )
      val otherProjectReportId =
          insertSeedFundReport(
              projectId = otherProjectId,
              year = 1993,
              status = SeedFundReportStatus.New,
          )
      val orgReportId = insertSeedFundReport(status = SeedFundReportStatus.New, year = 1994)

      service.on(ProjectRenamedEvent(projectId, "Old Name", "New Name"))

      assertEquals(
          mapOf(
              submittedReportId to "Old Name",
              lockedReportId to "New Name",
              newReportId to "New Name",
              otherProjectReportId to "Other",
              orgReportId to null,
          ),
          seedFundReportsDao.findAll().associate { it.id to it.projectName },
      )
    }
  }

  private fun insertSampleWithdrawals(
      speciesId: SpeciesId,
      nurseryId: FacilityId,
      plantingSiteId: PlantingSiteId,
      projectId: ProjectId? = null,
  ) {
    insertBatch(
        facilityId = nurseryId,
        germinatingQuantity = 100,
        activeGrowthQuantity = 200,
        hardeningOffQuantity = 400,
        projectId = projectId,
        readyQuantity = 300,
        speciesId = speciesId,
    )

    insertNurseryWithdrawal(facilityId = nurseryId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(readyQuantityWithdrawn = 100, activeGrowthQuantityWithdrawn = 52)

    insertNurseryWithdrawal(facilityId = nurseryId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(
        readyQuantityWithdrawn = 20,
        activeGrowthQuantityWithdrawn = 30,
        hardeningOffQuantityWithdrawn = 40,
    )
    insertDelivery(plantingSiteId = plantingSiteId)
    insertPlanting(plantingSiteId = plantingSiteId, speciesId = speciesId)
  }
}
