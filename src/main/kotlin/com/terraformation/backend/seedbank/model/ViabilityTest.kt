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
import kotlin.math.sign

data class ViabilityTestResultModel(
    val id: ViabilityTestResultId? = null,
    val recordingDate: LocalDate,
    val seedsGerminated: Int,
    val testId: ViabilityTestId? = null
)

data class ViabilityTestModel(
    val accessionId: AccessionId? = null,
    val endDate: LocalDate? = null,
    val id: ViabilityTestId? = null,
    val notes: String? = null,
    val remaining: SeedQuantityModel? = null,
    val seedsCompromised: Int? = null,
    val seedsEmpty: Int? = null,
    val seedsFilled: Int? = null,
    val seedsTested: Int? = null,
    val seedType: ViabilityTestSeedType? = null,
    val staffResponsible: String? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val testResults: Collection<ViabilityTestResultModel>? = null,
    val testType: ViabilityTestType,
    val totalSeedsGerminated: Int? = null,
    val treatment: ViabilityTestTreatment? = null,
    val viabilityPercent: Int? = null,
    val withdrawnByName: String? = null,
    val withdrawnByUserId: UserId? = null,
) {
  fun validateV1() {
    assertNotMixingCutAndGerminationResults()
    assertValidSeedCounts()
  }

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

    assertNotMixingCutAndGerminationResults()
    assertValidSeedCounts()
  }

  private fun assertNotMixingCutAndGerminationResults() {
    if (testType == ViabilityTestType.Cut) {
      if (!testResults.isNullOrEmpty()) {
        throw IllegalArgumentException("Cut tests cannot have germination test results")
      }
    } else if (seedsCompromised != null || seedsEmpty != null || seedsFilled != null) {
      throw IllegalArgumentException("Germination tests cannot have cut test results")
    }
  }

  private fun assertValidSeedCounts() {
    if (seedsCompromised?.sign == -1 ||
        seedsEmpty?.sign == -1 ||
        seedsFilled?.sign == -1 ||
        seedsTested?.sign == -1) {
      throw IllegalArgumentException("Seed counts cannot be negative")
    }

    if ((seedsCompromised ?: 0) + (seedsEmpty ?: 0) + (seedsFilled ?: 0) > (seedsTested ?: 0)) {
      throw IllegalArgumentException("Cut test cannot have results for more seeds than were tested")
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

    return copy(seedsTested = seedsTested ?: 1, substrate = newSubstrate)
  }

  fun fieldsEqual(other: ViabilityTestModel): Boolean {
    return endDate == other.endDate &&
        notes == other.notes &&
        remaining.equalsIgnoreScale(other.remaining) &&
        seedsCompromised == other.seedsCompromised &&
        seedsEmpty == other.seedsEmpty &&
        seedsFilled == other.seedsFilled &&
        seedsTested == other.seedsTested &&
        seedType == other.seedType &&
        staffResponsible == other.staffResponsible &&
        startDate == other.startDate &&
        substrate == other.substrate &&
        testType == other.testType &&
        totalSeedsGerminated == other.totalSeedsGerminated &&
        treatment == other.treatment &&
        viabilityPercent == other.viabilityPercent &&
        withdrawnByUserId == other.withdrawnByUserId
  }

  fun calculateLatestRecordingDate(): LocalDate? {
    return testResults?.maxOfOrNull { it.recordingDate }
  }

  private fun calculateTotalSeedsGerminated(): Int? {
    return testResults?.sumOf { it.seedsGerminated }
  }

  fun calculateViabilityPercent(): Int? {
    return when (testType) {
      ViabilityTestType.Cut ->
          if (seedsTested != null && seedsFilled != null && seedsTested > 0) {
            seedsFilled * 100 / seedsTested
          } else {
            null
          }
      ViabilityTestType.Lab,
      ViabilityTestType.Nursery ->
          calculateTotalSeedsGerminated()?.let { germinated ->
            val sown = seedsTested ?: 0
            if (sown > 0) {
              germinated * 100 / sown
            } else {
              null
            }
          }
    }
  }

  fun withCalculatedValues(): ViabilityTestModel {
    return copy(
        totalSeedsGerminated = calculateTotalSeedsGerminated(),
        viabilityPercent = calculateViabilityPercent(),
    )
  }
}
