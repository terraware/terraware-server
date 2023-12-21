package com.terraformation.backend.report

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
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
import com.terraformation.backend.db.ReportAlreadySubmittedException
import com.terraformation.backend.db.ReportLockedException
import com.terraformation.backend.db.ReportNotLockedException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.ReportBodyModelV1
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import com.terraformation.backend.report.model.SustainableDevelopmentGoal
import com.terraformation.backend.report.render.ReportRenderer
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

class ReportServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(REPORTS)

  private val clock = TestClock()
  private val googleDriveWriter: GoogleDriveWriter = mockk()
  private val messages = Messages()
  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val publisher = TestEventPublisher()
  private val parentStore by lazy { ParentStore(dslContext) }
  private val reportRenderer: ReportRenderer = mockk()
  private val reportStore by lazy {
    ReportStore(
        clock,
        dslContext,
        publisher,
        facilitiesDao,
        objectMapper,
        parentStore,
        projectsDao,
        reportsDao,
    )
  }
  private val scheduler: JobScheduler = mockk()

  private val service by lazy {
    ReportService(
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
            nurseryWithdrawalsDao),
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
            dslContext,
            publisher,
            monitoringPlotsDao,
            parentStore,
            plantingSeasonsDao,
            plantingSitesDao,
            plantingSubzonesDao,
            plantingZonesDao),
        ProjectStore(clock, dslContext, publisher, projectsDao),
        reportRenderer,
        reportStore,
        scheduler,
        SpeciesStore(clock, dslContext, speciesDao, speciesEcosystemTypesDao, speciesProblemsDao),
        SystemUser(usersDao),
    )
  }

  @BeforeEach
  fun setUp() {
    every { user.canCreateReport(any()) } returns true
    every { user.canDeleteReport(any()) } returns true
    every { user.canListReports(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadReport(any()) } returns true
    every { user.canUpdateReport(any()) } returns true
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)

    insertUser()
    insertOrganization()
    insertOrganizationUser(user.userId, organizationId, Role.Admin)
  }

  @Nested
  inner class Create {
    @Test
    fun `populates all server-generated fields`() {
      val nurseryId = FacilityId(1)
      val plantingSiteId = PlantingSiteId(1)
      val seedBankId = FacilityId(2)
      val speciesId = insertSpecies(growthForm = GrowthForm.Shrub, scientificName = "My species")

      insertFacility(
          nurseryId,
          buildCompletedDate = LocalDate.of(2023, 3, 1),
          buildStartedDate = LocalDate.of(2023, 2, 1),
          capacity = 1000,
          type = FacilityType.Nursery,
      )

      insertFacility(
          seedBankId,
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

      insertPlantingSite(id = plantingSiteId)

      insertSampleWithdrawals(speciesId, nurseryId, plantingSiteId)

      val expected =
          ReportModel(
              ReportBodyModelV1(
                  annualDetails = ReportBodyModelV1.AnnualDetails(),
                  isAnnual = true,
                  nurseries =
                      listOf(
                          ReportBodyModelV1.Nursery(
                              buildCompletedDate = LocalDate.of(2023, 3, 1),
                              buildCompletedDateEditable = false,
                              buildStartedDate = LocalDate.of(2023, 2, 1),
                              buildStartedDateEditable = false,
                              capacity = 1000,
                              id = nurseryId,
                              // 152 dead / (498 remaining + 200 total withdrawn) = 21.8%
                              mortalityRate = 22,
                              name = "Facility $nurseryId",
                              // inventory (200 not-ready, 300 ready) +
                              // outplanting withdrawals (20 not-ready, 30 ready)
                              totalPlantsPropagated = 550,
                          ),
                      ),
                  organizationName = "Organization 1",
                  plantingSites =
                      listOf(
                          ReportBodyModelV1.PlantingSite(
                              id = plantingSiteId,
                              name = "Site $plantingSiteId",
                              species =
                                  listOf(
                                      ReportBodyModelV1.PlantingSite.Species(
                                          growthForm = GrowthForm.Shrub,
                                          id = speciesId,
                                          scientificName = "My species",
                                      ),
                                  ),
                          ),
                      ),
                  seedBanks =
                      listOf(
                          ReportBodyModelV1.SeedBank(
                              id = seedBankId,
                              name = "Facility $seedBankId",
                              operationStartedDate = LocalDate.of(2023, 4, 1),
                              operationStartedDateEditable = false,
                              totalSeedsStored = 10,
                          ),
                      ),
                  totalNurseries = 1,
                  totalPlantingSites = 1,
                  totalSeedBanks = 1,
              ),
              ReportMetadata(
                  ReportId(1),
                  organizationId = organizationId,
                  quarter = 4,
                  status = ReportStatus.New,
                  year = 1969,
              ),
          )

      val created = service.create(organizationId)

      val actual = reportStore.fetchOneById(created.id)

      assertJsonEquals(expected, actual)
    }

    @Test
    fun `only includes project-related values in project-level report bodies`() {
      val projectNurseryId = FacilityId(1)
      val nonProjectNurseryId = FacilityId(2)
      val projectSeedBankId = FacilityId(3)
      val nonProjectSeedBankId = FacilityId(4)
      val projectPlantingSiteId = PlantingSiteId(1)
      val nonProjectPlantingSiteId = PlantingSiteId(2)
      val otherProjectPlantingSiteId = PlantingSiteId(3)

      val projectId = insertProject(name = "Test Project")
      val otherProjectId = insertProject(name = "Other Project")
      val speciesId = insertSpecies(growthForm = GrowthForm.Shrub, scientificName = "My species")

      insertFacility(nonProjectNurseryId, type = FacilityType.Nursery)
      insertFacility(nonProjectSeedBankId, type = FacilityType.SeedBank)
      insertPlantingSite(id = nonProjectPlantingSiteId)
      insertPlantingSite(id = otherProjectPlantingSiteId, projectId = otherProjectId)

      insertFacility(
          projectNurseryId,
          buildCompletedDate = LocalDate.of(2023, 3, 1),
          buildStartedDate = LocalDate.of(2023, 2, 1),
          capacity = 1000,
          type = FacilityType.Nursery,
      )

      insertFacility(
          projectSeedBankId,
          operationStartedDate = LocalDate.of(2023, 4, 1),
          type = FacilityType.SeedBank,
      )

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

      insertPlantingSite(id = projectPlantingSiteId, projectId = projectId)

      // This sample will be counted toward the project: the batches are tagged with the project ID.
      insertSampleWithdrawals(speciesId, projectNurseryId, projectPlantingSiteId, projectId)

      // These samples won't count toward totalPlantsPropagatedForProject but because they are at a
      // nursery that has batches for the project, they will count toward totalPlantsPropagated.
      insertSampleWithdrawals(speciesId, projectNurseryId, nonProjectPlantingSiteId)
      insertSampleWithdrawals(
          speciesId, projectNurseryId, otherProjectPlantingSiteId, otherProjectId)

      // This sample is at a different nursery which won't appear in the per-project report because
      // it has no batches for the project in question.
      insertSampleWithdrawals(speciesId, nonProjectNurseryId, projectPlantingSiteId)

      val expected =
          ReportModel(
              ReportBodyModelV1(
                  annualDetails = ReportBodyModelV1.AnnualDetails(),
                  isAnnual = true,
                  nurseries =
                      listOf(
                          ReportBodyModelV1.Nursery(
                              buildCompletedDate = LocalDate.of(2023, 3, 1),
                              buildCompletedDateEditable = false,
                              buildStartedDate = LocalDate.of(2023, 2, 1),
                              buildStartedDateEditable = false,
                              capacity = 1000,
                              id = projectNurseryId,
                              // 152 dead / (498 remaining + 200 total withdrawn) = 21.8%
                              mortalityRate = 22,
                              name = "Facility $projectNurseryId",
                              // Project-level total only counts one of the four samples:
                              //   inventory (200 not-ready, 300 ready) +
                              //   outplanting withdrawals (20 not-ready, 30 ready)
                              totalPlantsPropagatedForProject = 550,
                              // Org-level total counts all three samples for this nursery (same
                              // numbers as above for each sample).
                              totalPlantsPropagated = 1650,
                          ),
                      ),
                  organizationName = "Organization 1",
                  plantingSites =
                      listOf(
                          ReportBodyModelV1.PlantingSite(
                              id = projectPlantingSiteId,
                              name = "Site $projectPlantingSiteId",
                              species =
                                  listOf(
                                      ReportBodyModelV1.PlantingSite.Species(
                                          growthForm = GrowthForm.Shrub,
                                          id = speciesId,
                                          scientificName = "My species",
                                      ),
                                  ),
                          ),
                      ),
                  seedBanks =
                      listOf(
                          ReportBodyModelV1.SeedBank(
                              id = projectSeedBankId,
                              name = "Facility $projectSeedBankId",
                              operationStartedDate = LocalDate.of(2023, 4, 1),
                              operationStartedDateEditable = false,
                              totalSeedsStored = 7,
                              totalSeedsStoredForProject = 1,
                          ),
                      ),
                  totalNurseries = 1,
                  totalPlantingSites = 1,
                  totalSeedBanks = 1,
              ),
              ReportMetadata(
                  ReportId(1),
                  organizationId = organizationId,
                  projectId = projectId,
                  projectName = "Test Project",
                  quarter = 4,
                  status = ReportStatus.New,
                  year = 1969,
              ),
          )

      val created = service.create(organizationId, projectId)

      val actual = reportStore.fetchOneById(created.id)

      assertJsonEquals(expected, actual)
    }

    @Test
    fun `does not create annual report for mid-year quarters`() {
      clock.instant = ZonedDateTime.of(2022, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      val created = service.create(organizationId)

      val body = reportStore.fetchOneById(created.id).body.toLatestVersion()

      assertFalse(body.isAnnual, "Is annual")
      assertNull(body.annualDetails, "Annual details")
    }

    @Test
    fun `creates annual report in December`() {
      clock.instant = ZonedDateTime.of(2022, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      val created = service.create(organizationId)

      val body = reportStore.fetchOneById(created.id).body.toLatestVersion()

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
      val nonReportingOrganization = OrganizationId(2)
      val alreadyInProgressOrganization = OrganizationId(3)

      insertOrganization(nonReportingOrganization)
      insertOrganization(alreadyInProgressOrganization)
      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertOrganizationInternalTag(alreadyInProgressOrganization, InternalTagIds.Reporter)
      insertReport(organizationId = alreadyInProgressOrganization, quarter = 4, year = 1969)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertNotNull(reportStore.fetchMetadataByOrganization(organizationId).firstOrNull())
    }

    @Test
    fun `does not create reports for organizations that have org-level reports disabled`() {
      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertOrganizationReportSettings(isEnabled = false)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(emptyList<Any>(), reportStore.fetchMetadataByOrganization(organizationId))
    }

    @Test
    fun `creates reports for projects that need them`() {
      val projectWithOlderReport = insertProject()
      val reportsEnabledProject = insertProject()

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertProjectReportSettings(projectId = projectWithOlderReport)
      insertProjectReportSettings(projectId = reportsEnabledProject, isEnabled = true)

      insertReport(projectId = projectWithOlderReport, quarter = 3, year = 1969)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          1,
          reportsDao.fetchByProjectId(reportsEnabledProject).size,
          "Should have created report for project with no existing reports and reports enabled")
      assertEquals(
          2,
          reportsDao.fetchByProjectId(projectWithOlderReport).size,
          "Should have created current-quarter report for project with older report")
    }

    @Test
    fun `creates reports for projects with no report settings`() {
      val projectId = insertProject()

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          1,
          reportsDao.fetchByProjectId(projectId).size,
          "Number of reports for project with no existing reports and no settings")
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
          reportsDao.fetchByProjectId(projectId),
          "Should not have created report for project with reports disabled")
    }

    @Test
    fun `does not create reports for projects that already have them`() {
      val projectId = insertProject()
      val reportId = insertReport(projectId = projectId, quarter = 4, year = 1969)

      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertProjectReportSettings(projectId = projectId, isEnabled = true)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(
          listOf(reportId),
          reportsDao.fetchByProjectId(projectId).map { it.id },
          "Should not have created additional report when one was already in progress")
    }

    @Test
    fun `does not create reports for projects whose organizations are not tagged as reporters`() {
      val projectId = insertProject()

      insertOrganizationReportSettings(organizationId, isEnabled = true)
      insertProjectReportSettings(projectId, isEnabled = true)

      service.createMissingReports(DailyTaskTimeArrivedEvent())

      assertEquals(emptyList<Any>(), reportsDao.findAll())
    }
  }

  @Nested
  inner class DeleteOrganization {
    @Test
    fun `deletes all reports for organization when organization deletion starts`() {
      val otherOrganizationId = OrganizationId(2)

      insertOrganization(otherOrganizationId)
      insertReport(year = 2000)
      insertReport(year = 2001)
      val otherOrgReportId = insertReport(organizationId = otherOrganizationId)

      service.on(OrganizationDeletionStartedEvent(organizationId))

      assertEquals(listOf(otherOrgReportId), reportsDao.findAll().map { it.id }, "Report IDs")

      assertIsEventListener<OrganizationDeletionStartedEvent>(service)
    }
  }

  @Nested
  inner class DeleteProject {
    @Test
    fun `deletes unsubmitted project-level reports when project is deleted`() {
      val deletedProjectId = insertProject()
      val keptProjectId = insertProject()
      val orgLevelReportId = insertReport()
      val keptProjectReportId = insertReport(projectId = keptProjectId)
      val deletedProjectReportId = insertReport(projectId = deletedProjectId)

      service.on(ProjectDeletionStartedEvent(deletedProjectId))

      projectsDao.deleteById(deletedProjectId)

      assertFalse(
          reportsDao.existsById(deletedProjectReportId),
          "Should have deleted report for deleted project")
      assertTrue(
          reportsDao.existsById(keptProjectReportId),
          "Should not have deleted report for non-deleted project")
      assertTrue(
          reportsDao.existsById(orgLevelReportId), "Should not have deleted org-level report")
    }

    @Test
    fun `keeps submitted project-level reports for deleted projects`() {
      val projectId = insertProject(name = "Test Project")
      val submittedReportId =
          insertReport(projectId = projectId, year = 1990, submittedBy = user.userId)

      service.on(ProjectDeletionStartedEvent(projectId))

      projectsDao.deleteById(projectId)

      val reportsRow = reportsDao.fetchOneById(submittedReportId)
      assertNotNull(reportsRow, "Should not have deleted submitted report")
      assertNull(reportsRow?.projectId, "Should have cleared project ID from submitted report")
      assertEquals(
          "Test Project",
          reportsRow?.projectName,
          "Should have kept project name on submitted report")
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
      val firstSeedBank = FacilityId(1)
      val firstNursery = FacilityId(2)
      val secondNursery = FacilityId(3)
      val secondSeedBank = FacilityId(4)
      val firstPlantingSite = PlantingSiteId(1)
      val secondPlantingSite = PlantingSiteId(2)

      insertFacility(
          firstSeedBank,
          type = FacilityType.SeedBank,
          buildCompletedDate = LocalDate.of(2023, 2, 2),
      )
      insertFacility(firstNursery, type = FacilityType.Nursery)
      insertPlantingSite(id = firstPlantingSite)

      insertAccession(
          AccessionsRow(
              facilityId = firstSeedBank,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Seeds,
          ),
      )

      val speciesId = insertSpecies(growthForm = GrowthForm.Forb, scientificName = "New species")
      insertBatch(
          facilityId = firstNursery,
          germinatingQuantity = 50,
          notReadyQuantity = 60,
          readyQuantity = 70,
          speciesId = speciesId,
      )
      val withdrawalId = insertWithdrawal(facilityId = firstNursery)
      val deliveryId =
          insertDelivery(plantingSiteId = firstPlantingSite, withdrawalId = withdrawalId)
      insertPlanting(deliveryId = deliveryId, speciesId = speciesId)

      val initialBody =
          ReportBodyModelV1(
              annualDetails =
                  ReportBodyModelV1.AnnualDetails(
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
                              ReportBodyModelV1.AnnualDetails.GoalProgress(
                                  SustainableDevelopmentGoal.CleanWater,
                                  "clean water progress",
                              ),
                          ),
                  ),
              isAnnual = true,
              nurseries =
                  listOf(
                      ReportBodyModelV1.Nursery(
                          buildCompletedDate = LocalDate.of(2023, 1, 2),
                          buildStartedDate = LocalDate.of(2023, 1, 1),
                          capacity = 1,
                          id = firstNursery,
                          mortalityRate = 0,
                          name = "old nursery name",
                          notes = "nursery notes",
                          totalPlantsPropagated = 130,
                          workers = ReportBodyModelV1.Workers(1, 2, 3),
                      ),
                  ),
              notes = "top-level notes",
              organizationName = "old org name",
              plantingSites =
                  listOf(
                      ReportBodyModelV1.PlantingSite(
                          id = firstPlantingSite,
                          mortalityRate = 10,
                          name = "old planting site name",
                          selected = false,
                          species =
                              listOf(
                                  ReportBodyModelV1.PlantingSite.Species(
                                      growthForm = GrowthForm.Forb,
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
                          workers = ReportBodyModelV1.Workers(4, 5, 6),
                      ),
                  ),
              seedBanks =
                  listOf(
                      ReportBodyModelV1.SeedBank(
                          buildCompletedDate = LocalDate.of(2023, 2, 2),
                          buildCompletedDateEditable = false,
                          buildStartedDate = LocalDate.of(2023, 3, 1),
                          id = firstSeedBank,
                          name = "old seedbank name",
                          notes = "seedbank notes",
                          operationStartedDate = LocalDate.of(2023, 4, 1),
                          totalSeedsStored = 1000L,
                          workers = ReportBodyModelV1.Workers(7, 8, 9),
                      ),
                  ),
              summaryOfProgress = "summary of progress",
              totalNurseries = 1,
              totalPlantingSites = 1,
              totalSeedBanks = 1,
          )

      val reportId = insertReport(body = objectMapper.writeValueAsString(initialBody))
      val initialMetadata = service.fetchOneById(reportId).metadata

      insertFacility(
          secondSeedBank,
          type = FacilityType.SeedBank,
          buildStartedDate = LocalDate.EPOCH,
      )
      insertFacility(secondNursery, type = FacilityType.Nursery)
      insertPlantingSite(id = secondPlantingSite)
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
          ReportModel(
              body =
                  initialBody.copy(
                      nurseries =
                          listOf(
                              initialBody.nurseries[0].copy(
                                  buildCompletedDate = LocalDate.of(2023, 1, 15),
                                  buildCompletedDateEditable = false,
                                  // 152 dead / (628 remaining + 200 total withdrawn) = 18.4%
                                  mortalityRate = 18,
                                  name = "Facility $firstNursery",
                                  // initial batch (60 not-ready, 70 ready) +
                                  // insertSampleWithdrawals batch (200 not-ready, 300 ready) +
                                  // outplanting withdrawals (20 not-ready, 30 ready)
                                  totalPlantsPropagated = 680,
                              ),
                              ReportBodyModelV1.Nursery(
                                  id = secondNursery,
                                  mortalityRate = 0,
                                  name = "Facility $secondNursery",
                                  totalPlantsPropagated = 0,
                              ),
                          ),
                      organizationName = "New org name",
                      plantingSites =
                          listOf(
                              initialBody.plantingSites[0].copy(
                                  name = "Site $firstPlantingSite",
                                  species =
                                      listOf(
                                          initialBody.plantingSites[0]
                                              .species[0]
                                              .copy(
                                                  scientificName = "New species",
                                              ),
                                      ),
                              ),
                              ReportBodyModelV1.PlantingSite(
                                  id = secondPlantingSite,
                                  name = "Site $secondPlantingSite",
                                  species =
                                      listOf(
                                          ReportBodyModelV1.PlantingSite.Species(
                                              growthForm = GrowthForm.Forb,
                                              id = speciesId,
                                              scientificName = "New species",
                                          ),
                                      ),
                              ),
                          ),
                      seedBanks =
                          listOf(
                              initialBody.seedBanks[0].copy(
                                  name = "Facility $firstSeedBank",
                                  totalSeedsStored = 10L,
                              ),
                              ReportBodyModelV1.SeedBank(
                                  buildStartedDate = LocalDate.EPOCH,
                                  buildStartedDateEditable = false,
                                  id = secondSeedBank,
                                  name = "Facility $secondSeedBank",
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
      assertFalse((actual.body as ReportBodyModelV1).seedBanks[1].buildStartedDateEditable)
    }

    @Test
    fun `does not refresh server-generated fields if report was already submitted`() {
      val reportId = insertReport(submittedBy = user.userId)

      val expected = service.fetchOneById(reportId)

      insertFacility(1, type = FacilityType.SeedBank)
      insertFacility(2, type = FacilityType.Nursery)
      insertPlantingSite(id = 3)
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
      val reportId = insertReport(lockedBy = user.userId)
      val seedBankId = FacilityId(1)
      insertFacility(seedBankId)

      val newNotes = "new notes"
      var calledWithSeedBanks: List<ReportBodyModelV1.SeedBank>? = null

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
      val reportId = insertReport()

      assertThrows<ReportNotLockedException> { service.update(reportId) { it } }
    }

    @Test
    fun `throws exception if report is locked by another user`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)
      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<ReportLockedException> { service.update(reportId) { it } }
    }

    @Test
    fun `throws exception if report is already submitted`() {
      val reportId = insertReport(submittedBy = user.userId)

      assertThrows<ReportAlreadySubmittedException> { service.update(reportId) { it } }
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
          insertReport(
              projectId = projectId,
              year = 1990,
              submittedBy = user.userId,
              status = ReportStatus.Submitted)
      val lockedReportId =
          insertReport(
              projectId = projectId,
              year = 1991,
              lockedBy = user.userId,
              status = ReportStatus.Locked)
      val newReportId = insertReport(projectId = projectId, year = 1992, status = ReportStatus.New)
      val otherProjectReportId =
          insertReport(projectId = otherProjectId, year = 1993, status = ReportStatus.New)
      val orgReportId = insertReport(status = ReportStatus.New, year = 1994)

      service.on(ProjectRenamedEvent(projectId, "Old Name", "New Name"))

      assertEquals(
          mapOf(
              submittedReportId to "Old Name",
              lockedReportId to "New Name",
              newReportId to "New Name",
              otherProjectReportId to "Other",
              orgReportId to null,
          ),
          reportsDao.findAll().associate { it.id to it.projectName })
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
        notReadyQuantity = 200,
        projectId = projectId,
        readyQuantity = 300,
        speciesId = speciesId,
    )

    insertWithdrawal(facilityId = nurseryId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(readyQuantityWithdrawn = 100, notReadyQuantityWithdrawn = 52)

    insertWithdrawal(facilityId = nurseryId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(readyQuantityWithdrawn = 20, notReadyQuantityWithdrawn = 30)
    insertDelivery(plantingSiteId = plantingSiteId)
    insertPlanting(plantingSiteId = plantingSiteId, speciesId = speciesId)
  }
}
