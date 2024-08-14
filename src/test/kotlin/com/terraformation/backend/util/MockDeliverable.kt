package com.terraformation.backend.util

import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.db.accelerator.DeliverableCategory
import io.mockk.every
import io.mockk.mockk

fun mockDeliverable(): DeliverableSubmissionModel {
  val deliverable: DeliverableSubmissionModel = mockk(relaxed = true)

  every { deliverable.name } answers { "FS" }
  every { deliverable.category } answers { DeliverableCategory.CarbonEligibility }

  return deliverable
}
