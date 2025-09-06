package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.file.FileService
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReportServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val reportStore = mockk<ReportStore>()
  private val fileService = mockk<FileService>()
  private val service: ReportService by lazy {
    ReportService(
        dslContext,
        fileService,
        reportPhotosDao,
        reportStore,
        publishedReportPhotosDao,
        SystemUser(usersDao),
    )
  }

  @BeforeEach
  fun setup() {
    every { reportStore.notifyUpcomingReports() } returns 1
  }

  @Test
  fun `notifies upcoming reports daily`() {
    service.on(DailyTaskTimeArrivedEvent())
    verify(exactly = 1) { reportStore.notifyUpcomingReports() }
    assertIsEventListener<DailyTaskTimeArrivedEvent>(service)
  }
}
