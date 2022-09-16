package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.UserId
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
    val seedsTested: Int? = null,
    val totalPercentGerminated: Int? = null,
    val totalSeedsGerminated: Int? = null,
    val notes: String? = null,
    val staffResponsible: String? = null,
    val testResults: Collection<ViabilityTestResultModel>? = null,
    val remaining: SeedQuantityModel? = null,
    val withdrawnByUserId: UserId? = null,
    val withdrawnByName: String? = null,
) {
  fun validateV2() {
    val isLab = testType == ViabilityTestType.Lab
    val isNursery = testType == ViabilityTestType.Nursery

    val substrateValidForTestType =
        when (substrate) {
          ViabilityTestSubstrate.Agar -> isLab
          ViabilityTestSubstrate.MediaMix -> isNursery
          ViabilityTestSubstrate.Moss -> isNursery
          ViabilityTestSubstrate.NurseryMedia -> isLab
          ViabilityTestSubstrate.Other -> isLab || isNursery
          ViabilityTestSubstrate.Paper -> isLab
          ViabilityTestSubstrate.PerliteVermiculite -> isNursery
          ViabilityTestSubstrate.Sand -> isLab || isNursery
          ViabilityTestSubstrate.Soil -> isNursery
          null -> true
        }
    if (!substrateValidForTestType) {
      throw IllegalArgumentException(
          "Substrate ${substrate?.displayName} not valid for test type ${testType.displayName}")
    }
  }

  fun toV1Compatible(): ViabilityTestModel {
    val newSubstrate =
        when (substrate) {
          ViabilityTestSubstrate.Agar,
          ViabilityTestSubstrate.NurseryMedia,
          ViabilityTestSubstrate.Other,
          ViabilityTestSubstrate.Paper -> substrate
          else -> null
        }

    return copy(substrate = newSubstrate)
  }

  fun toV2Compatible(): ViabilityTestModel {
    val newSubstrate: ViabilityTestSubstrate? =
        if (testType == ViabilityTestType.Lab) {
          substrate
        } else {
          // None of the v1 substrates apart from "Other" is valid for v2 nursery tests.
          if (substrate == ViabilityTestSubstrate.Other) {
            substrate
          } else {
            null
          }
        }

    return copy(substrate = newSubstrate)
  }

  fun fieldsEqual(other: ViabilityTestModel): Boolean {
    return endDate == other.endDate &&
        notes == other.notes &&
        remaining.equalsIgnoreScale(other.remaining) &&
        seedsTested == other.seedsTested &&
        seedType == other.seedType &&
        staffResponsible == other.staffResponsible &&
        startDate == other.startDate &&
        substrate == other.substrate &&
        testType == other.testType &&
        totalPercentGerminated == other.totalPercentGerminated &&
        totalSeedsGerminated == other.totalSeedsGerminated &&
        treatment == other.treatment &&
        withdrawnByUserId == other.withdrawnByUserId
  }

  fun calculateLatestRecordingDate(): LocalDate? {
    return testResults?.maxOfOrNull { it.recordingDate }
  }

  private fun calculateTotalSeedsGerminated(): Int? {
    return testResults?.sumOf { it.seedsGerminated }
  }

  fun calculateTotalPercentGerminated(): Int? {
    return calculateTotalSeedsGerminated()?.let { germinated ->
      val sown = seedsTested ?: 0
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
