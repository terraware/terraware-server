package com.terraformation.backend.report.db

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ReportAlreadySubmittedException
import com.terraformation.backend.db.ReportLockedException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.ReportNotLockedException
import com.terraformation.backend.db.ReportSubmittedException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.mockUser
import com.terraformation.backend.report.ReportNotCompleteException
import com.terraformation.backend.report.event.ReportCreatedEvent
import com.terraformation.backend.report.event.ReportDeletionStartedEvent
import com.terraformation.backend.report.event.ReportSubmittedEvent
import com.terraformation.backend.report.model.ReportBodyModelV1
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import com.terraformation.backend.time.quarter
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ReportStoreTest : DatabaseTest(), RunsAsUser {
  override val tablesToResetSequences = listOf(REPORTS)
  override val user = mockUser()

  private val defaultTime = ZonedDateTime.of(2023, 7, 3, 2, 1, 0, 0, ZoneOffset.UTC)

  private val clock = TestClock(defaultTime.toInstant())
  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val publisher = TestEventPublisher()
  private val store by lazy {
    ReportStore(clock, dslContext, publisher, objectMapper, reportsDao, facilitiesDao)
  }

  @BeforeEach
  fun setUp() {
    insertSiteData()

    every { user.canCreateReport(any()) } returns true
    every { user.canDeleteReport(any()) } returns true
    every { user.canListReports(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadReport(any()) } returns true
    every { user.canUpdateReport(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `sets defaults for metadata fields`() {
      val expected =
          ReportMetadata(
              id = ReportId(1),
              organizationId = organizationId,
              quarter = 2,
              status = ReportStatus.New,
              year = 2023,
          )

      val actual = store.create(organizationId, ReportBodyModelV1(organizationName = "org"))

      assertEquals(expected, actual)
    }

    @Test
    fun `publishes ReportCreatedEvent`() {
      val metadata = store.create(organizationId, ReportBodyModelV1(organizationName = "org"))

      publisher.assertEventPublished(ReportCreatedEvent(metadata))
    }

    @Test
    fun `uses previous year if current date is in Q1`() {
      clock.instant = ZonedDateTime.of(2022, 3, 15, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      val expected =
          ReportMetadata(
              id = ReportId(1),
              organizationId = organizationId,
              quarter = 4,
              status = ReportStatus.New,
              year = 2021,
          )

      val actual = store.create(organizationId, ReportBodyModelV1(organizationName = "org"))

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if user has no permission to create reports`() {
      every { user.canCreateReport(organizationId) } returns false

      assertThrows<AccessDeniedException> {
        store.create(organizationId, ReportBodyModelV1(organizationName = "org"))
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
          listOf(reportId2), reportsDao.findAll().map { it.id }, "Report IDs after deletion")
    }

    @Test
    fun `publishes event`() {
      val reportId = insertReport()

      store.delete(reportId)

      publisher.assertEventPublished(ReportDeletionStartedEvent(reportId))
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canDeleteReport(any()) } returns false

      val reportId = insertReport()

      assertThrows<AccessDeniedException> { store.delete(reportId) }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns report with deserialized body`() {
      val body = ReportBodyModelV1(organizationName = "org")

      val reportId = insertReport()

      val expected =
          ReportModel(
              body,
              ReportMetadata(
                  id = reportId,
                  organizationId = organizationId,
                  quarter = 1,
                  status = ReportStatus.New,
                  year = 1970,
              ))

      val actual = store.fetchOneById(reportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if user has no permission to read report`() {
      val reportId = insertReport()

      every { user.canReadReport(reportId) } returns false

      assertThrows<ReportNotFoundException> { store.fetchOneById(reportId) }
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
      assertEquals(ReportStatus.Locked, lockedReport.metadata.status, "Status")
    }

    @Test
    fun `overwrites existing lock if force is true`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)

      val reportId = insertReport(lockedBy = otherUserId, lockedTime = Instant.EPOCH)

      store.lock(reportId, true)

      val lockedReport = store.fetchOneById(reportId)

      assertEquals(currentUser().userId, lockedReport.metadata.lockedBy, "Locked by")
      assertEquals(clock.instant, lockedReport.metadata.lockedTime, "Locked time")
      assertEquals(ReportStatus.Locked, lockedReport.metadata.status, "Status")
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
      val otherUserId = UserId(10)
      insertUser(otherUserId)

      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<ReportLockedException> { store.lock(reportId) }
    }

    @Test
    fun `throws exception if report already submitted`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)

      val reportId = insertReport(status = ReportStatus.Submitted, submittedBy = otherUserId)

      assertThrows<ReportSubmittedException> { store.lock(reportId) }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport()

      every { user.canUpdateReport(reportId) } returns false

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

      Assertions.assertNull(unlockedReport.metadata.lockedBy, "Locked by")
      Assertions.assertNull(unlockedReport.metadata.lockedTime, "Locked time")
      assertEquals(ReportStatus.InProgress, unlockedReport.metadata.status, "Status")
    }

    @Test
    fun `is a no-op if report is not locked`() {
      val reportId = insertReport()

      store.unlock(reportId)

      val unlockedReport = store.fetchOneById(reportId)

      Assertions.assertNull(unlockedReport.metadata.lockedBy, "Locked by")
      Assertions.assertNull(unlockedReport.metadata.lockedTime, "Locked time")
      assertEquals(ReportStatus.InProgress, unlockedReport.metadata.status, "Status")
    }

    @Test
    fun `throws exception if report is locked by someone else`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)

      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<ReportLockedException> { store.unlock(reportId) }
    }

    @Test
    fun `throws exception if report already submitted`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)

      val reportId = insertReport(status = ReportStatus.Submitted, submittedBy = otherUserId)

      assertThrows<ReportSubmittedException> { store.unlock(reportId) }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport()

      every { user.canUpdateReport(reportId) } returns false

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
                          id = FacilityId(1),
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
                  status = ReportStatus.Locked,
                  year = 1970,
              ))

      val actual = store.fetchOneById(reportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if report is already submitted`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)

      val reportId = insertReport(status = ReportStatus.Submitted, submittedBy = otherUserId)

      assertThrows<ReportAlreadySubmittedException> {
        store.update(reportId, ReportBodyModelV1(organizationName = "org"))
      }
    }

    @Test
    fun `throws exception if report is not locked`() {
      val reportId = insertReport()

      assertThrows<ReportNotLockedException> {
        store.update(reportId, ReportBodyModelV1(organizationName = "org"))
      }
    }

    @Test
    fun `throws exception if report is locked by someone else`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)

      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<ReportLockedException> {
        store.update(reportId, ReportBodyModelV1(organizationName = "org"))
      }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport()

      every { user.canUpdateReport(reportId) } returns false

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
              status = ReportStatus.Submitted,
              submittedBy = user.userId,
              submittedTime = clock.instant,
              year = 1970,
          )

      publisher.assertEventPublished(ReportSubmittedEvent(reportId, body))
      assertEquals(expectedMetadata, store.fetchOneById(reportId).metadata)
    }

    @Test
    fun `saves seed bank and nursery information`() {
      insertFacility(1)
      insertFacility(2)
      insertFacility(3, type = FacilityType.Nursery)
      val body =
          ReportBodyModelV1(
              organizationName = "org",
              summaryOfProgress = "All's well",
              seedBanks =
                  listOf(
                      ReportBodyModelV1.SeedBank(
                          id = FacilityId(1),
                          name = "bank",
                          buildStartedDate = LocalDate.EPOCH,
                          buildCompletedDate = LocalDate.EPOCH,
                          operationStartedDate = LocalDate.EPOCH,
                          workers = ReportBodyModelV1.Workers(1, 1, 1),
                      ),
                      ReportBodyModelV1.SeedBank(
                          id = FacilityId(2),
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
                          id = FacilityId(3),
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

      val seedBankResult = getFacilityById(FacilityId(1))
      assertEquals(seedBankResult.buildStartedDate, LocalDate.EPOCH)
      assertEquals(seedBankResult.buildCompletedDate, LocalDate.EPOCH)
      assertEquals(seedBankResult.operationStartedDate, LocalDate.EPOCH)

      val unselectedSeedBankResult = getFacilityById(FacilityId(2))
      assertNull(unselectedSeedBankResult.buildStartedDate)
      assertNull(unselectedSeedBankResult.buildCompletedDate)
      assertNull(unselectedSeedBankResult.operationStartedDate)

      val nurseryResult = getFacilityById(FacilityId(3))
      assertEquals(nurseryResult.buildStartedDate, LocalDate.EPOCH)
      assertEquals(nurseryResult.buildCompletedDate, LocalDate.EPOCH)
      assertEquals(nurseryResult.operationStartedDate, LocalDate.EPOCH)
      assertEquals(nurseryResult.capacity, 100)
    }

    @Test
    fun `throws exception if report is incomplete`() {
      insertFacility(1)
      val body =
          ReportBodyModelV1(
              organizationName = "org",
              seedBanks = listOf(ReportBodyModelV1.SeedBank(id = FacilityId(1), name = "bank")))
      val reportId =
          insertReport(lockedBy = user.userId, body = objectMapper.writeValueAsString(body))

      assertThrows<ReportNotCompleteException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if report is not locked`() {
      val reportId = insertReport()

      assertThrows<ReportNotLockedException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if report is locked by another user`() {
      val otherUserId = UserId(10)
      insertUser(otherUserId)
      val reportId = insertReport(lockedBy = otherUserId)

      assertThrows<ReportLockedException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if report is already submitted`() {
      val reportId = insertReport(submittedBy = user.userId)

      assertThrows<ReportAlreadySubmittedException> { store.submit(reportId) }
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val reportId = insertReport(lockedBy = user.userId)

      every { user.canUpdateReport(any()) } returns false

      assertThrows<AccessDeniedException> { store.submit(reportId) }
    }
  }

  @Nested
  inner class FindOrganizationsForCreate {
    private val nonReportingOrganizationId = OrganizationId(2)
    private val missingReportOrganizationId = OrganizationId(3)

    @BeforeEach
    fun setUp() {
      insertOrganization(nonReportingOrganizationId)
      insertOrganization(missingReportOrganizationId)
      insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
      insertOrganizationInternalTag(missingReportOrganizationId, InternalTagIds.Reporter)
    }

    @Test
    fun `ignores reports from earlier quarters`() {
      val twoQuartersAgo = defaultTime.minusMonths(6)
      insertReport(quarter = twoQuartersAgo.quarter, year = twoQuartersAgo.year)

      assertEquals(
          listOf(organizationId, missingReportOrganizationId), store.findOrganizationsForCreate())
    }

    @Test
    fun `only includes organizations without existing reports`() {
      insertReport(quarter = defaultTime.quarter - 1, year = defaultTime.year)

      assertEquals(listOf(missingReportOrganizationId), store.findOrganizationsForCreate())
    }

    @Test
    fun `only includes organizations with permission to create reports`() {
      every { user.canCreateReport(organizationId) } returns false

      assertEquals(listOf(missingReportOrganizationId), store.findOrganizationsForCreate())
    }
  }
}
