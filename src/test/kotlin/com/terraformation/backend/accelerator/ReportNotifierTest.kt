package com.terraformation.backend.accelerator

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.AcceleratorReportSubmittedEvent
import com.terraformation.backend.accelerator.event.RateLimitedAcceleratorReportSubmittedEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.accelerator.ReportId
import org.junit.jupiter.api.Test

class ReportNotifierTest {
  private val rateLimitedEventPublisher = TestEventPublisher()

  private val notifier = ReportNotifier(rateLimitedEventPublisher)

  @Test
  fun `publishes RateLimitedAcceleratorReportSubmittedEvent on report submission`() {
    val event = AcceleratorReportSubmittedEvent(ReportId(1))

    notifier.on(event)
    rateLimitedEventPublisher.assertEventPublished(
        RateLimitedAcceleratorReportSubmittedEvent(event.reportId)
    )
    assertIsEventListener<AcceleratorReportSubmittedEvent>(notifier)
  }
}
