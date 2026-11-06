package com.terraformation.backend.nursery.model

@ConsistentCopyVisibility
data class SpeciesStageTimeModel
private constructor(
    val averageDays: Double?,
    val batchCount: Int,
    val typicalMaxDays: Double?,
    val typicalMinDays: Double?,
    val typicalRangeBatchCount: Int,
) {
  companion object {
    const val MIN_AVERAGE_BATCH_COUNT = 3
    const val MIN_TYPICAL_RANGE_BATCH_COUNT = 5
    const val MIN_TYPICAL_RANGE_FINISHED_QUANTITY = 10.0

    /**
     * Builds a model from raw aggregates, nulling averages below [MIN_AVERAGE_BATCH_COUNT] batches.
     * The typical range is null without an average or [MIN_TYPICAL_RANGE_BATCH_COUNT] qualifying
     * batches.
     */
    fun of(
        averageDays: Double? = null,
        batchCount: Int = 0,
        typicalMaxDays: Double? = null,
        typicalMinDays: Double? = null,
        typicalRangeBatchCount: Int = 0,
    ): SpeciesStageTimeModel {
      val average = averageDays.takeIf { batchCount >= MIN_AVERAGE_BATCH_COUNT }
      val hasRange = average != null && typicalRangeBatchCount >= MIN_TYPICAL_RANGE_BATCH_COUNT
      return SpeciesStageTimeModel(
          averageDays = average,
          batchCount = batchCount,
          typicalMaxDays = typicalMaxDays.takeIf { hasRange },
          typicalMinDays = typicalMinDays.takeIf { hasRange },
          typicalRangeBatchCount = typicalRangeBatchCount,
      )
    }
  }
}
