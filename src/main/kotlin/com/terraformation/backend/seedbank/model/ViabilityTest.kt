package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.ViabilityTestResultId
import com.terraformation.backend.db.ViabilityTestSeedType
import com.terraformation.backend.db.ViabilityTestSubstrate
import com.terraformation.backend.db.ViabilityTestTreatment
import com.terraformation.backend.db.ViabilityTestType
import java.time.LocalDate

data class ViabilityTestResultModel(
    val id: ViabilityTestResultId? = null,
    val testId: ViabilityTestId? = null,
    val recordingDate: LocalDate,
    val seedsGerminated: Int
)

data class ViabilityTestModel(
    val id: ViabilityTestId? = null,
    val accessionId: AccessionId? = null,
    val testType: ViabilityTestType,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val seedType: ViabilityTestSeedType? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val treatment: ViabilityTestTreatment? = null,
    val seedsSown: Int? = null,
    val totalPercentGerminated: Int? = null,
    val totalSeedsGerminated: Int? = null,
    val notes: String? = null,
    val staffResponsible: String? = null,
    val testResults: Collection<ViabilityTestResultModel>? = null,
    val remaining: SeedQuantityModel? = null,
) {
  fun fieldsEqual(other: ViabilityTestModel): Boolean {
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
    return testResults?.maxOfOrNull { it.recordingDate }
  }

  private fun calculateTotalSeedsGerminated(): Int? {
    return testResults?.sumOf { it.seedsGerminated }
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

  fun withCalculatedValues(): ViabilityTestModel {
    return copy(
        totalPercentGerminated = calculateTotalPercentGerminated(),
        totalSeedsGerminated = calculateTotalSeedsGerminated(),
    )
  }
}
