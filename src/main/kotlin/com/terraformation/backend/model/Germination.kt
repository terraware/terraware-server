package com.terraformation.backend.model

import com.terraformation.backend.db.GerminationSeedType
import com.terraformation.backend.db.GerminationSubstrate
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.GerminationTreatment
import java.time.LocalDate

data class GerminationModel(
    val id: Long? = null,
    val testId: Long? = null,
    val recordingDate: LocalDate,
    val seedsGerminated: Int
)

data class GerminationTestModel(
    val id: Long? = null,
    val accessionId: Long? = null,
    val testType: GerminationTestType,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val seedType: GerminationSeedType? = null,
    val substrate: GerminationSubstrate? = null,
    val treatment: GerminationTreatment? = null,
    val seedsSown: Int? = null,
    val totalPercentGerminated: Int? = null,
    val totalSeedsGerminated: Int? = null,
    val notes: String? = null,
    val staffResponsible: String? = null,
    val germinations: Collection<GerminationModel>? = null,
    val remaining: SeedQuantityModel? = null,
) {
  fun fieldsEqual(other: GerminationTestModel): Boolean {
    return endDate == other.endDate &&
        notes == other.notes &&
        remaining.equalsIgnoreScale(other.remaining) &&
        seedsSown == other.seedsSown &&
        seedType == other.seedType &&
        staffResponsible == other.staffResponsible &&
        startDate == other.startDate &&
        substrate == other.substrate &&
        testType == other.testType &&
        totalPercentGerminated == other.totalPercentGerminated &&
        totalSeedsGerminated == other.totalSeedsGerminated &&
        treatment == other.treatment
  }

  fun calculateLatestRecordingDate(): LocalDate? {
    return germinations?.maxOfOrNull { it.recordingDate }
  }

  fun calculateTotalSeedsGerminated(): Int? {
    return germinations?.sumOf { it.seedsGerminated }
  }

  fun calculateTotalPercentGerminated(): Int? {
    return calculateTotalSeedsGerminated()?.let { germinated ->
      val sown = seedsSown ?: 0
      if (sown > 0) {
        germinated * 100 / sown
      } else {
        null
      }
    }
  }
}
