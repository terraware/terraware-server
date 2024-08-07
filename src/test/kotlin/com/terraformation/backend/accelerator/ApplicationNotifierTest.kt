package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ApplicationReviewedEvent
import com.terraformation.backend.accelerator.event.ApplicationStatusUpdatedEvent
import com.terraformation.backend.accelerator.model.ExternalApplicationStatus
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.mockUser
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationNotifierTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val notifier: ApplicationNotifier by lazy {
    ApplicationNotifier(
        clock,
        ApplicationStore(
            clock, countriesDao, mockk(), dslContext, eventPublisher, mockk(), organizationsDao),
        eventPublisher,
        mockk(),
        SystemUser(usersDao),
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertApplication(status = ApplicationStatus.Accepted)
  }

  @Nested
  inner class NotifyIfNoNewerStatus {
    @Test
    fun `does not publish event if status is changed`() {
      val existing = applicationsDao.findById(inserted.applicationId)!!
      applicationsDao.update(existing.copy(applicationStatusId = ApplicationStatus.NotAccepted))
      notifier.notifyIfNoNewerStatus(
          ApplicationReviewedEvent(inserted.applicationId, ExternalApplicationStatus.Accepted))

      eventPublisher.assertEventNotPublished<ApplicationStatusUpdatedEvent>()
    }

    @Test
    fun `publishes event if status is unchanged`() {
      notifier.notifyIfNoNewerStatus(
          ApplicationReviewedEvent(inserted.applicationId, ExternalApplicationStatus.Accepted))

      eventPublisher.assertEventPublished(
          ApplicationStatusUpdatedEvent(inserted.applicationId, ExternalApplicationStatus.Accepted))
    }
  }
}
