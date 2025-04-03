package com.terraformation.backend.accelerator

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.AcceleratorReportSubmittedEvent
import com.terraformation.backend.accelerator.event.RateLimitedAcceleratorReportSubmittedEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.default_schema.ProjectId
import org.junit.jupiter.api.Test

class ReportNotifierTest {
  private val rateLimitedEventPublisher = TestEventPublisher()

  private val notifier = ReportNotifier(rateLimitedEventPublisher)

  @Test
  fun `publishes RateLimitedAcceleratorReportSubmittedEvent on report submission`() {
    val event = AcceleratorReportSubmittedEvent(ReportId(1), ProjectId(2))

    notifier.on(event)
    rateLimitedEventPublisher.assertEventPublished(
        RateLimitedAcceleratorReportSubmittedEvent(event.reportId, event.projectId))
    assertIsEventListener<AcceleratorReportSubmittedEvent>(notifier)
  }
}
