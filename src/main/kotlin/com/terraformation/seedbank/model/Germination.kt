package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.GerminationSeedType
import com.terraformation.seedbank.db.GerminationSubstrate
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.GerminationTreatment
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
  val seedType: GerminationSeedType?
    get() = null
  val substrate: GerminationSubstrate?
    get() = null
  val treatment: GerminationTreatment?
    get() = null
  val seedsSown: Int?
    get() = null
  val notes: String?
    get() = null
  val germinations: Collection<GerminationFields>?
    get() = null
}

data class GerminationTestModel(
    override val id: Long,
    val accessionId: Long,
    override val testType: GerminationTestType,
    override val startDate: LocalDate? = null,
    override val seedType: GerminationSeedType? = null,
    override val substrate: GerminationSubstrate? = null,
    override val treatment: GerminationTreatment? = null,
    override val seedsSown: Int? = null,
    override val notes: String? = null,
    override val germinations: Collection<GerminationModel>? = null
) : GerminationTestFields
