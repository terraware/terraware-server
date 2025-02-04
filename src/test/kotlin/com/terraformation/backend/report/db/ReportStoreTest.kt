package com.terraformation.backend.report.db

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.SeedFundReportAlreadySubmittedException
import com.terraformation.backend.db.SeedFundReportLockedException
import com.terraformation.backend.db.SeedFundReportNotFoundException
import com.terraformation.backend.db.SeedFundReportNotLockedException
import com.terraformation.backend.db.SeedFundReportSubmittedException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.mockUser
import com.terraformation.backend.report.ReportNotCompleteException
import com.terraformation.backend.report.event.ReportCreatedEvent
import com.terraformation.backend.report.event.ReportDeletionStartedEvent
import com.terraformation.backend.report.event.ReportSubmittedEvent
import com.terraformation.backend.report.model.ReportBodyModelV1
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import com.terraformation.backend.report.model.ReportProjectSettingsModel
import com.terraformation.backend.report.model.ReportSettingsModel
import com.terraformation.backend.time.quarter
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ReportStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val defaultTime = ZonedDateTime.of(2023, 7, 3, 2, 1, 0, 0, ZoneOffset.UTC)

  private val clock = TestClock(defaultTime.toInstant())
  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val publisher = TestEventPublisher()
  private val store by lazy {
    ReportStore(
        clock,
        dslContext,
        publisher,
        facilitiesDao,
        objectMapper,
        ParentStore(dslContext),
        projectsDao,
        seedFundReportsDao,
    )
  }

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertFacility()

    every { user.canCreateSeedFundReport(any()) } returns true
    every { user.canDeleteSeedFundReport(any()) } returns true
    every { user.canListSeedFundReports(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSeedFundReport(any()) } returns true
    every { user.canUpdateOrganization(any()) } returns true
    every { user.canUpdateProject(any()) } returns true
    every { user.canUpdateSeedFundReport(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `sets defaults for metadata fields`() {
      val actual = store.create(organizationId, body = ReportBodyModelV1(organizationName = "org"))

      val expected =
          ReportMetadata(
              id = actual.id,
              organizationId = organizationId,
              quarter = 2,
              status = SeedFundReportStatus.New,
              year = 2023,
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `publishes ReportCreatedEvent`() {
      val metadata =
          store.create(organizationId, body = ReportBodyModelV1(organizationName = "org"))

      publisher.assertEventPublished(ReportCreatedEvent(metadata))
    }

    @Test
    fun `uses previous year if current date is in Q1`() {
      clock.instant = ZonedDateTime.of(2022, 3, 15, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      val actual = store.create(organizationId, body = ReportBodyModelV1(organizationName = "org"))

      val expected =
          ReportMetadata(
              id = actual.id,
              organizationId = organizationId,
              quarter = 4,
              status = SeedFundReportStatus.New,
              year = 2021,
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `creates Q4 report on December 1`() {
      clock.instant = ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      val actual = store.create(organizationId, body = ReportBodyModelV1(organizationName = "org"))

      val expected =
          ReportMetadata(
              id = actual.id,
              organizationId = organizationId,
              quarter = 4,
              status = SeedFundReportStatus.New,
              year = 2023,
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `creates project-level reports`() {
      val projectId = insertProject(name = "Test Project")

      val actual =
          store.create(organizationId, projectId, ReportBodyModelV1(organizationName = "org"))

      val expected =
          ReportMetadata(
              id = actual.id,
              organizationId = organizationId,
              projectId = projectId,
              projectName = "Test Project",
              quarter = 2,
              status = SeedFundReportStatus.New,
              year = 2023,
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if user has no permission to create reports`() {
      every { user.canCreateSeedFundReport(organizationId) } returns false

      assertThrows<AccessDeniedException> {
        store.create(organizationId, body = ReportBodyModelV1(organizationName = "org"))
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes report from database`() {
      val reportId1 = insertReport(year = 2000)
      val reportId2 = insertReport(year = 2001)

      store.delete(reportId1)

      assertEquals(
          listOf(reportId2),
          seedFundReportsDao.findAll().map { it.id },
          "Report IDs after deletion")
    }

    @Test
    fun `publishes event`() {
      val reportId = insertReport()

      store.delete(reportId)

      publisher.assertEventPublished(ReportDeletionStartedEvent(reportId))
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canDeleteSeedFundReport(any()) } returns false

      val reportId = insertReport()

      assertThrows<AccessDeniedException> { store.delete(reportId) }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns report with deserialized body`() {
      val body = ReportBodyModelV1(organizationName = "org")

      val projectId = insertProject(name = "Test Project")
      val reportId = insertReport(projectId = projectId)

      val expected =
          ReportModel(
              body,
              ReportMetadata(
                  id = reportId,
                  organizationId = organizationId,
                  projectId = projectId,
                  projectName = "Test Project",
                  quarter = 1,
                  status = SeedFundReportStatus.New,
                  year = 1970,
              ))

      val actual = store.fetchOneById(reportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if user has no permission to read report`() {
      val reportId = insertReport()

      every { user.canReadSeedFundReport(reportId) } returns false

      assertThrows<SeedFundReportNotFoundException> { store.fetchOneById(reportId) }
    }
  }

  @Nested
  inner class Lock {
    @Test
    fun `acquires lock if report not locked`() {
      val reportId = insertReport()

      store.lock(reportId)

      val lockedReport = store.fetchOneById(reportId)

      assertEquals(currentUser().userId, lockedReport.metadata.lockedBy, "Locked by")
      assertEquals(clock.instant, lockedReport.metadata.lockedTime, "Locked time")
      assertEquals(SeedFundReportStatus.Locked, lockedReport.metadata.status, "Status")
    }

    @Test
    fun `overwrites existing lock if force is true`() {
      val otherUserId = insertUser()

      val reportId = insertReport(lockedBy = otherUserId, lockedTime = Instant.EPOCH)

      store.lock(reportId, true)

      val lockedReport = store.fetchOneById(reportId)

      assertEquals(currentUser().userId, lockedReport.metadata.lockedBy, "Locked by")
      assertEquals(clock.instant, lockedReport.metadata.lockedTime, "Locked time")
      assertEquals(SeedFundReportStatus.Locked, lockedReport.metadata.status, "Status")
    }

    @Test
    fun `updates locked time if current user holds lock`() {
      val reportId = insertReport(lockedBy = user.userId)

      store.lock(reportId)

      val lockedReport = store.fetchOneById(reportId)

      assertEquals(clock.instant, lockedReport.metadata.lockedTime)
    }

    @Test
    fun `throws exception if report already locked and force is not true`() {
      val otherUserId = insertUser()

      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<SeedFundReportLockedException> { store.lock(reportId) }
    }

    @Test
    fun `throws exception if report already submitted`() {
      val otherUserId = insertUser()

      val reportId =
          insertReport(status = SeedFundReportStatus.Submitted, submittedBy = otherUserId)

      assertThrows<SeedFundReportSubmittedException> { store.lock(reportId) }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport()

      every { user.canUpdateSeedFundReport(reportId) } returns false

      assertThrows<AccessDeniedException> { store.lock(reportId) }
    }
  }

  @Nested
  inner class Unlock {
    @Test
    fun `releases lock`() {
      val reportId = insertReport(lockedBy = user.userId)

      store.unlock(reportId)

      val unlockedReport = store.fetchOneById(reportId)

      assertNull(unlockedReport.metadata.lockedBy, "Locked by")
      assertNull(unlockedReport.metadata.lockedTime, "Locked time")
      assertEquals(SeedFundReportStatus.InProgress, unlockedReport.metadata.status, "Status")
    }

    @Test
    fun `is a no-op if report is not locked`() {
      val reportId = insertReport()

      store.unlock(reportId)

      val unlockedReport = store.fetchOneById(reportId)

      assertNull(unlockedReport.metadata.lockedBy, "Locked by")
      assertNull(unlockedReport.metadata.lockedTime, "Locked time")
      assertEquals(SeedFundReportStatus.InProgress, unlockedReport.metadata.status, "Status")
    }

    @Test
    fun `throws exception if report is locked by someone else`() {
      val otherUserId = insertUser()

      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<SeedFundReportLockedException> { store.unlock(reportId) }
    }

    @Test
    fun `throws exception if report already submitted`() {
      val otherUserId = insertUser()

      val reportId =
          insertReport(status = SeedFundReportStatus.Submitted, submittedBy = otherUserId)

      assertThrows<SeedFundReportSubmittedException> { store.unlock(reportId) }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport()

      every { user.canUpdateSeedFundReport(reportId) } returns false

      assertThrows<AccessDeniedException> { store.unlock(reportId) }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates report body and metadata`() {
      val reportId = insertReport(lockedBy = currentUser().userId)

      val newBody =
          ReportBodyModelV1(
              notes = "notes",
              organizationName = "new name",
              seedBanks =
                  listOf(
                      ReportBodyModelV1.SeedBank(
                          buildCompletedDate = null,
                          buildCompletedDateEditable = true,
                          buildStartedDate = null,
                          buildStartedDateEditable = true,
                          id = inserted.facilityId,
                          name = "bank",
                          notes = "notes",
                          operationStartedDate = null,
                          operationStartedDateEditable = true,
                          totalSeedsStored = 123L,
                          workers = ReportBodyModelV1.Workers(1, 2, 3))))

      store.update(reportId, newBody)

      val expected =
          ReportModel(
              newBody,
              ReportMetadata(
                  id = reportId,
                  lockedBy = currentUser().userId,
                  lockedTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = clock.instant,
                  organizationId = organizationId,
                  quarter = 1,
                  status = SeedFundReportStatus.Locked,
                  year = 1970,
              ))

      val actual = store.fetchOneById(reportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if report is already submitted`() {
      val otherUserId = insertUser()

      val reportId =
          insertReport(status = SeedFundReportStatus.Submitted, submittedBy = otherUserId)

      assertThrows<SeedFundReportAlreadySubmittedException> {
        store.update(reportId, ReportBodyModelV1(organizationName = "org"))
      }
    }

    @Test
    fun `throws exception if report is not locked`() {
      val reportId = insertReport()

      assertThrows<SeedFundReportNotLockedException> {
        store.update(reportId, ReportBodyModelV1(organizationName = "org"))
      }
    }

    @Test
    fun `throws exception if report is locked by someone else`() {
      val otherUserId = insertUser()

      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<SeedFundReportLockedException> {
        store.update(reportId, ReportBodyModelV1(organizationName = "org"))
      }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport()

      every { user.canUpdateSeedFundReport(reportId) } returns false

      assertThrows<AccessDeniedException> {
        store.update(reportId, ReportBodyModelV1(organizationName = "org"))
      }
    }
  }

  @Nested
  inner class Submit {
    @Test
    fun `marks report as submitted and publishes event`() {
      val body = ReportBodyModelV1(organizationName = "org")
      val reportId = insertReport(lockedBy = user.userId)

      store.submit(reportId)

      val expectedMetadata =
          ReportMetadata(
              id = reportId,
              organizationId = organizationId,
              quarter = 1,
              status = SeedFundReportStatus.Submitted,
              submittedBy = user.userId,
              submittedTime = clock.instant,
              year = 1970,
          )

      publisher.assertEventPublished(ReportSubmittedEvent(reportId, body))
      assertEquals(expectedMetadata, store.fetchOneById(reportId).metadata)
    }

    @Test
    fun `saves seed bank and nursery information`() {
      val facilityId1 = insertFacility()
      val facilityId2 = insertFacility()
      val facilityId3 = insertFacility(type = FacilityType.Nursery)
      val body =
          ReportBodyModelV1(
              organizationName = "org",
              summaryOfProgress = "All's well",
              seedBanks =
                  listOf(
                      ReportBodyModelV1.SeedBank(
                          id = facilityId1,
                          name = "bank",
                          buildStartedDate = LocalDate.EPOCH,
                          buildCompletedDate = LocalDate.EPOCH,
                          operationStartedDate = LocalDate.EPOCH,
                          workers = ReportBodyModelV1.Workers(1, 1, 1),
                      ),
                      ReportBodyModelV1.SeedBank(
                          id = facilityId2,
                          selected = false,
                          name = "bank",
                          buildStartedDate = LocalDate.EPOCH,
                          buildCompletedDate = LocalDate.EPOCH,
                          operationStartedDate = LocalDate.EPOCH,
                          workers = ReportBodyModelV1.Workers(1, 1, 1),
                      ),
                  ),
              nurseries =
                  listOf(
                      ReportBodyModelV1.Nursery(
                          id = facilityId3,
                          name = "nursery",
                          buildStartedDate = LocalDate.EPOCH,
                          buildCompletedDate = LocalDate.EPOCH,
                          operationStartedDate = LocalDate.EPOCH,
                          capacity = 100,
                          mortalityRate = 10,
                          workers = ReportBodyModelV1.Workers(1, 1, 1),
                          totalPlantsPropagated = 100,
                      ),
                  ),
          )
      val reportId =
          insertReport(lockedBy = user.userId, body = objectMapper.writeValueAsString(body))

      store.submit(reportId)

      val seedBankResult = getFacilityById(facilityId1)
      assertEquals(seedBankResult.buildStartedDate, LocalDate.EPOCH)
      assertEquals(seedBankResult.buildCompletedDate, LocalDate.EPOCH)
      assertEquals(seedBankResult.operationStartedDate, LocalDate.EPOCH)

      val unselectedSeedBankResult = getFacilityById(facilityId2)
      assertNull(unselectedSeedBankResult.buildStartedDate)
      assertNull(unselectedSeedBankResult.buildCompletedDate)
      assertNull(unselectedSeedBankResult.operationStartedDate)

      val nurseryResult = getFacilityById(facilityId3)
      assertEquals(nurseryResult.buildStartedDate, LocalDate.EPOCH)
      assertEquals(nurseryResult.buildCompletedDate, LocalDate.EPOCH)
      assertEquals(nurseryResult.operationStartedDate, LocalDate.EPOCH)
      assertEquals(nurseryResult.capacity, 100)
    }

    @Test
    fun `throws exception if report is incomplete`() {
      val facilityId = insertFacility()
      val body =
          ReportBodyModelV1(
              organizationName = "org",
              seedBanks = listOf(ReportBodyModelV1.SeedBank(id = facilityId, name = "bank")))
      val reportId =
          insertReport(lockedBy = user.userId, body = objectMapper.writeValueAsString(body))

      assertThrows<ReportNotCompleteException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if report is not locked`() {
      val reportId = insertReport()

      assertThrows<SeedFundReportNotLockedException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if report is locked by another user`() {
      val otherUserId = insertUser()
      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<SeedFundReportLockedException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if report is already submitted`() {
      val reportId = insertReport(submittedBy = user.userId)

      assertThrows<SeedFundReportAlreadySubmittedException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport(lockedBy = user.userId)

      every { user.canUpdateSeedFundReport(any()) } returns false

      assertThrows<AccessDeniedException> { store.submit(reportId) }
    }
  }

  @Nested
  inner class FindOrganizationsForCreate {
    private lateinit var nonReportingOrganizationId: OrganizationId
    private lateinit var missingReportOrganizationId: OrganizationId

    @BeforeEach
    fun setUp() {
      nonReportingOrganizationId = insertOrganization()
      missingReportOrganizationId = insertOrganization()
      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertOrganizationInternalTag(missingReportOrganizationId, InternalTagIds.Reporter)
    }

    @Test
    fun `ignores reports from earlier quarters`() {
      val twoQuartersAgo = defaultTime.minusMonths(6)
      insertReport(
          organizationId = organizationId,
          quarter = twoQuartersAgo.quarter,
          year = twoQuartersAgo.year)

      assertEquals(
          listOf(organizationId, missingReportOrganizationId), store.findOrganizationsForCreate())
    }

    @Test
    fun `only includes organizations without existing reports`() {
      insertReport(
          organizationId = organizationId,
          quarter = defaultTime.quarter - 1,
          year = defaultTime.year)

      assertEquals(listOf(missingReportOrganizationId), store.findOrganizationsForCreate())
    }

    @Test
    fun `only includes organizations with permission to create reports`() {
      every { user.canCreateSeedFundReport(organizationId) } returns false

      assertEquals(listOf(missingReportOrganizationId), store.findOrganizationsForCreate())
    }
  }

  @Nested
  inner class FetchSettings {
    @Test
    fun `returns defaults if no settings have been saved`() {
      assertEquals(
          ReportSettingsModel(
              isConfigured = false,
              organizationId = organizationId,
              organizationEnabled = true,
              projects = emptyList()),
          store.fetchSettingsByOrganization(organizationId),
          "Result with no projects")

      val projectId = insertProject()

      assertEquals(
          ReportSettingsModel(
              isConfigured = false,
              organizationId = organizationId,
              organizationEnabled = true,
              projects =
                  listOf(
                      ReportProjectSettingsModel(
                          projectId = projectId, isConfigured = false, isEnabled = true))),
          store.fetchSettingsByOrganization(organizationId),
          "Result with unconfigured project")
    }

    @Test
    fun `supports organization-level settings`() {
      insertOrganizationReportSettings(isEnabled = false)

      assertEquals(
          ReportSettingsModel(
              isConfigured = true,
              organizationId = organizationId,
              organizationEnabled = false,
              projects = emptyList(),
          ),
          store.fetchSettingsByOrganization(organizationId),
          "Organization reports should be disabled")

      val otherOrganizationId = insertOrganization()
      insertOrganizationReportSettings(isEnabled = true)

      assertEquals(
          ReportSettingsModel(
              isConfigured = true,
              organizationId = otherOrganizationId,
              organizationEnabled = true,
              projects = emptyList()),
          store.fetchSettingsByOrganization(otherOrganizationId),
          "Organization reports should be enabled")
    }

    @Test
    fun `supports project-level settings`() {
      insertOrganizationReportSettings(isEnabled = false)
      val unconfiguredProjectId = insertProject()
      val projectId1 = insertProject()
      val projectId2 = insertProject()
      insertProjectReportSettings(projectId = projectId1, isEnabled = true)
      insertProjectReportSettings(projectId = projectId2, isEnabled = false)

      assertEquals(
          ReportSettingsModel(
              isConfigured = true,
              organizationId = organizationId,
              organizationEnabled = false,
              projects =
                  listOf(
                      ReportProjectSettingsModel(
                          projectId = unconfiguredProjectId,
                          isConfigured = false,
                          isEnabled = true),
                      ReportProjectSettingsModel(
                          projectId = projectId1, isConfigured = true, isEnabled = true),
                      ReportProjectSettingsModel(
                          projectId = projectId2, isConfigured = true, isEnabled = false),
                  )),
          store.fetchSettingsByOrganization(organizationId),
          "Should include list of projects with reports enabled")

      organizationReportSettingsDao.update(
          organizationReportSettingsDao
              .fetchOneByOrganizationId(organizationId)!!
              .copy(isEnabled = true))

      assertEquals(
          ReportSettingsModel(
              isConfigured = true,
              organizationId = organizationId,
              organizationEnabled = true,
              projects =
                  listOf(
                      ReportProjectSettingsModel(
                          projectId = unconfiguredProjectId,
                          isConfigured = false,
                          isEnabled = true),
                      ReportProjectSettingsModel(
                          projectId = projectId1, isConfigured = true, isEnabled = true),
                      ReportProjectSettingsModel(
                          projectId = projectId2, isConfigured = true, isEnabled = false),
                  )),
          store.fetchSettingsByOrganization(organizationId),
          "Should include list of projects when organization reporting is enabled")
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      every { user.canReadOrganization(any()) } returns false

      assertThrows<OrganizationNotFoundException> {
        store.fetchSettingsByOrganization(organizationId)
      }
    }
  }

  @Nested
  inner class UpdateSettings {
    @Test
    fun `supports enabling organization-level reports`() {
      val settings =
          ReportSettingsModel(
              isConfigured = true,
              organizationId = organizationId,
              organizationEnabled = true,
              projects = emptyList())

      store.updateSettings(settings)

      assertEquals(settings, store.fetchSettingsByOrganization(organizationId))
    }

    @Test
    fun `supports disabling organization-level reports`() {
      insertOrganizationReportSettings(isEnabled = true)

      val settings =
          ReportSettingsModel(
              isConfigured = true,
              organizationId = organizationId,
              organizationEnabled = false,
              projects = emptyList())

      store.updateSettings(settings)

      assertEquals(settings, store.fetchSettingsByOrganization(organizationId))
    }

    @Test
    fun `throws exception if projects are in wrong organization`() {
      insertOrganization()
      val otherProjectId = insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.updateSettings(
            ReportSettingsModel(
                isConfigured = true,
                organizationId = organizationId,
                organizationEnabled = true,
                projects =
                    listOf(
                        ReportProjectSettingsModel(projectId = otherProjectId, isEnabled = true))))
      }
    }

    @Test
    fun `throws exception if no permission to update organization`() {
      every { user.canUpdateOrganization(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.updateSettings(
            ReportSettingsModel(
                isConfigured = true,
                organizationId = organizationId,
                organizationEnabled = true,
                projects = emptyList()))
      }
    }
  }
}
