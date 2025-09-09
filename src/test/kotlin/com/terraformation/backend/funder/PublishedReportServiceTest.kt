package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class PublishedReportServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val fileService = mockk<FileService>()
  private val service: PublishedReportService by lazy {
    PublishedReportService(
        fileService,
        publishedReportPhotosDao,
    )
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  private lateinit var reportId: ReportId

  private val content = byteArrayOf(1, 2, 3, 4)
  private val inputStream: SizedInputStream = SizedInputStream(ByteArrayInputStream(content), 1L)

  @BeforeEach
  fun setup() {
    insertFundingEntity()

    organizationId = insertOrganization(timeZone = ZoneOffset.UTC)
    projectId = insertProject()
    insertProjectReportConfig()
    reportId = insertReport()
    insertPublishedReport()

    every { fileService.readFile(any(), any(), any()) } returns inputStream
  }

  @Nested
  inner class ReadReportPhoto {
    @Test
    fun `throws exception if no permission to read published report`() {
      val fileId = insertFile()
      insertReportPhoto()
      insertPublishedReportPhoto()

      assertThrows<ReportNotFoundException>("No permission") { service.readPhoto(reportId, fileId) }

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertDoesNotThrow("Read Only") { service.readPhoto(reportId, fileId) }

      val funderId = insertUser(type = UserType.Funder)
      insertFundingEntityUser()
      switchToUser(funderId)
      assertDoesNotThrow("Funding Entity User") { service.readPhoto(reportId, fileId) }
    }

    @Test
    fun `returns file stream`() {
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      val fileId = insertFile()
      insertReportPhoto()
      insertPublishedReportPhoto()

      val inputStream = service.readPhoto(reportId, fileId)
      verify(exactly = 1) { fileService.readFile(fileId) }
      assertArrayEquals(content, inputStream.readAllBytes(), "Photo data")
    }
  }
}
