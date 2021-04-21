package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.GerminationSeedType
import com.terraformation.seedbank.db.GerminationSubstrate
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.GerminationTreatment
import java.time.Clock
import java.time.LocalDate

interface GerminationFields {
  val recordingDate: LocalDate
  val seedsGerminated: Int
}

data class GerminationModel(
    val id: Long,
    val testId: Long,
    override val recordingDate: LocalDate,
    override val seedsGerminated: Int
) : GerminationFields

interface GerminationTestFields {
  val id: Long?
    get() = null
  val testType: GerminationTestType
  val startDate: LocalDate?
    get() = null
  val endDate: LocalDate?
    get() = null
  val seedType: GerminationSeedType?
    get() = null
  val substrate: GerminationSubstrate?
    get() = null
  val treatment: GerminationTreatment?
    get() = null
  val seedsSown: Int?
    get() = null
  val totalSeedsGerminated: Int?
    get() = null
  val totalPercentGerminated: Int?
    get() = null
  val notes: String?
    get() = null
  val staffResponsible: String?
    get() = null
  val germinations: Collection<GerminationFields>?
    get() = null

  fun fieldsEqual(other: GerminationTestFields): Boolean {
    return notes == other.notes &&
        seedsSown == other.seedsSown &&
        seedType == other.seedType &&
        staffResponsible == other.staffResponsible &&
        startDate == other.startDate &&
        substrate == other.substrate &&
        testType == other.testType &&
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

data class GerminationTestModel(
    override val id: Long,
    val accessionId: Long,
    override val testType: GerminationTestType,
    override val startDate: LocalDate? = null,
    override val endDate: LocalDate? = null,
    override val seedType: GerminationSeedType? = null,
    override val substrate: GerminationSubstrate? = null,
    override val treatment: GerminationTreatment? = null,
    override val seedsSown: Int? = null,
    override val totalPercentGerminated: Int? = null,
    override val totalSeedsGerminated: Int? = null,
    override val notes: String? = null,
    override val staffResponsible: String? = null,
    override val germinations: Collection<GerminationModel>? = null
) : GerminationTestFields {
  fun toWithdrawal(withdrawalId: Long?, clock: Clock): GerminationTestWithdrawal? {
    if (seedsSown == null) {
      return null
    }

    return GerminationTestWithdrawal(
        accessionId = accessionId,
        date = startDate ?: LocalDate.now(clock),
        germinationTestId = id,
        gramsWithdrawn = null,
        id = withdrawalId,
        seedsWithdrawn = seedsSown,
    )
  }
}
