package com.terraformation.backend.nursery.api

import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.nursery.model.StageTimeStats
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class BatchPayloadTest {
  @Test
  fun `converts time in stage stats to payload values`() {
    val now = Instant.parse("2025-10-10T00:00:00Z")
    val stats =
        mapOf(
            BatchStage.ActiveGrowth to
                StageTimeStats(
                    asOfTime = Instant.parse("2025-10-06T00:00:00Z"),
                    cleared = false,
                    countedDays = 2.0,
                    countedQuantity = 2,
                    inStage = 2,
                    openDays = 2.0,
                    stage = BatchStage.ActiveGrowth,
                ),
            BatchStage.Germinating to
                StageTimeStats(
                    asOfTime = Instant.parse("2025-09-30T00:00:00Z"),
                    cleared = false,
                    countedDays = 0.4,
                    countedQuantity = 1,
                    inStage = 0,
                    openDays = 0.4,
                    stage = BatchStage.Germinating,
                ),
            BatchStage.HardeningOff to
                StageTimeStats(
                    asOfTime = Instant.parse("2025-10-08T00:00:00Z"),
                    cleared = true,
                    countedDays = 1.0,
                    countedQuantity = 1,
                    inStage = 1,
                    openDays = 1.0,
                    stage = BatchStage.HardeningOff,
                ),
            BatchStage.Ready to
                StageTimeStats(
                    asOfTime = Instant.parse("2025-10-08T00:00:00Z"),
                    cleared = false,
                    countedDays = 0.0,
                    countedQuantity = 0,
                    inStage = 0,
                    openDays = 0.0,
                    stage = BatchStage.Ready,
                ),
        )

    assertEquals(
        BatchTimeInStagePayload(
            activeGrowth =
                BatchStageTimePayload(
                    cleared = false,
                    countedQuantity = 2,
                    days = 5,
                    inProgress = true,
                ),
            germinating =
                BatchStageTimePayload(
                    cleared = false,
                    countedQuantity = 1,
                    days = 0,
                    inProgress = false,
                ),
            hardeningOff =
                BatchStageTimePayload(
                    cleared = true,
                    countedQuantity = 1,
                    days = null,
                    inProgress = true,
                ),
            ready =
                BatchStageTimePayload(
                    cleared = false,
                    countedQuantity = 0,
                    days = null,
                    inProgress = false,
                ),
        ),
        BatchTimeInStagePayload.of(stats, now),
        "Rounded time-in-stage payload",
    )
    assertNull(BatchTimeInStagePayload.of(emptyMap(), now), "Unavailable time-in-stage payload")
  }
}
