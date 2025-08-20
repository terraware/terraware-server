package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestResultId
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import java.time.LocalDate
import kotlin.math.sign

data class ViabilityTestResultModel(
    val id: ViabilityTestResultId? = null,
    val recordingDate: LocalDate,
    val seedsGerminated: Int,
    val testId: ViabilityTestId? = null,
)

data class ViabilityTestModel(
    val accessionId: AccessionId? = null,
    val endDate: LocalDate? = null,
    val id: ViabilityTestId? = null,
    val notes: String? = null,
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
    val treatment: SeedTreatment? = null,
    val viabilityPercent: Int? = null,
    val withdrawnByName: String? = null,
    val withdrawnByUserId: UserId? = null,
) {
  fun validate() {
    val isLab = testType == ViabilityTestType.Lab
    val isNursery = testType == ViabilityTestType.Nursery

    val substrateValidForTestType =
        when (substrate) {
          ViabilityTestSubstrate.Agar -> isLab
          ViabilityTestSubstrate.MediaMix -> isNursery
          ViabilityTestSubstrate.Moss -> isNursery
          ViabilityTestSubstrate.None -> isLab || isNursery
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
          "Substrate ${substrate?.jsonValue} not valid for test type ${testType.jsonValue}"
      )
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
    if (
        seedsCompromised?.sign == -1 ||
            seedsEmpty?.sign == -1 ||
            seedsFilled?.sign == -1 ||
            seedsTested?.sign == -1
    ) {
      throw IllegalArgumentException("Seed counts cannot be negative")
    }

    if ((seedsCompromised ?: 0) + (seedsEmpty ?: 0) + (seedsFilled ?: 0) > (seedsTested ?: 0)) {
      throw IllegalArgumentException("Cut test cannot have results for more seeds than were tested")
    }
  }

  fun fieldsEqual(other: ViabilityTestModel): Boolean {
    return endDate == other.endDate &&
        notes == other.notes &&
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
