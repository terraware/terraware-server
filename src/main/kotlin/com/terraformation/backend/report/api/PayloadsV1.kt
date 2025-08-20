package com.terraformation.backend.report.api

import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.report.model.LatestSeedFundReportBodyModel
import com.terraformation.backend.report.model.SeedFundReportBodyModelV1
import com.terraformation.backend.report.model.SeedFundReportMetadata
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate

/**
 * Declares the names and types of the editable fields in a version 1 report. Both the read and
 * write payloads implement this to ensure they stay in sync.
 *
 * Generally, all the fields here should be nullable since we need to be able to handle saving work
 * in progress before all the required information is filled out.
 */
interface EditableReportFieldsV1 : EditableReportFields {
  val annualDetails: AnnualDetails?
  val notes: String?
  val nurseries: List<Nursery>
  val plantingSites: List<PlantingSite>
  val seedBanks: List<SeedBank>
  val summaryOfProgress: String?

  interface Nursery {
    val buildCompletedDate: LocalDate?
    val buildStartedDate: LocalDate?
    val capacity: Int?
    val id: FacilityId
    val notes: String?
    val operationStartedDate: LocalDate?
    val selected: Boolean
    val workers: Workers
  }

  interface PlantingSite {
    val id: PlantingSiteId
    val mortalityRate: Int?
    val notes: String?
    val selected: Boolean
    val species: List<Species>
    val totalPlantedArea: Int?
    val totalPlantingSiteArea: Int?
    val totalPlantsPlanted: Int?
    val totalTreesPlanted: Int?
    val workers: Workers

    interface Species {
      val id: SpeciesId
      val mortalityRateInField: Int?
      val totalPlanted: Int?
    }
  }

  interface SeedBank {
    val buildCompletedDate: LocalDate?
    val buildStartedDate: LocalDate?
    val id: FacilityId
    val notes: String?
    val operationStartedDate: LocalDate?
    val selected: Boolean
    val workers: Workers
  }

  interface Workers {
    val femalePaidWorkers: Int?
    val paidWorkers: Int?
    val volunteers: Int?
  }

  interface AnnualDetails {
    val bestMonthsForObservation: Set<Int>
    val budgetNarrativeSummary: String?
    val catalyticDetail: String?
    val challenges: String?
    val isCatalytic: Boolean
    val keyLessons: String?
    val nextSteps: String?
    val opportunities: String?
    val projectImpact: String?
    val projectSummary: String?
    val socialImpact: String?
    val successStories: String?
    val sustainableDevelopmentGoals: List<GoalProgress>

    interface GoalProgress {
      val goal: SustainableDevelopmentGoal
      val progress: String?
    }
  }
}

@JsonTypeName("1")
data class GetReportPayloadV1(
    override val annualDetails: AnnualDetailsPayloadV1?,
    override val id: SeedFundReportId,
    val isAnnual: Boolean,
    override val lockedByName: String?,
    override val lockedByUserId: UserId?,
    override val lockedTime: Instant?,
    override val modifiedByName: String?,
    override val modifiedByUserId: UserId?,
    override val modifiedTime: Instant?,
    override val notes: String?,
    override val nurseries: List<GetNurseryV1>,
    val organizationName: String,
    override val plantingSites: List<GetPlantingSiteV1>,
    override val projectId: ProjectId?,
    override val projectName: String?,
    override val quarter: Int,
    override val seedBanks: List<GetSeedBankV1>,
    override val status: SeedFundReportStatus,
    override val submittedByName: String?,
    override val submittedByUserId: UserId?,
    override val submittedTime: Instant?,
    override val summaryOfProgress: String?,
    val totalNurseries: Int,
    val totalPlantingSites: Int,
    val totalSeedBanks: Int,
    override val year: Int,
) : EditableReportFieldsV1, GetReportPayload {
  constructor(
      metadata: SeedFundReportMetadata,
      body: SeedFundReportBodyModelV1,
      getFullName: (UserId) -> String?,
  ) : this(
      annualDetails = body.annualDetails?.let { AnnualDetailsPayloadV1(it) },
      id = metadata.id,
      isAnnual = body.isAnnual,
      lockedByName = metadata.lockedBy?.let { getFullName(it) },
      lockedByUserId = metadata.lockedBy,
      lockedTime = metadata.lockedTime,
      modifiedByName = metadata.modifiedBy?.let { getFullName(it) },
      modifiedByUserId = metadata.modifiedBy,
      modifiedTime = metadata.modifiedTime,
      notes = body.notes,
      nurseries = body.nurseries.map { GetNurseryV1(it) },
      organizationName = body.organizationName,
      plantingSites = body.plantingSites.map { GetPlantingSiteV1(it) },
      projectId = metadata.projectId,
      projectName = metadata.projectName,
      quarter = metadata.quarter,
      seedBanks = body.seedBanks.map { GetSeedBankV1(it) },
      status = metadata.status,
      submittedByName = metadata.submittedBy?.let { getFullName(it) },
      submittedByUserId = metadata.submittedBy,
      submittedTime = metadata.submittedTime,
      summaryOfProgress = body.summaryOfProgress,
      totalNurseries = body.totalNurseries,
      totalPlantingSites = body.totalPlantingSites,
      totalSeedBanks = body.totalSeedBanks,
      year = metadata.year,
  )

  data class GetNurseryV1(
      override val buildCompletedDate: LocalDate?,
      val buildCompletedDateEditable: Boolean,
      override val buildStartedDate: LocalDate?,
      val buildStartedDateEditable: Boolean,
      override val capacity: Int?,
      override val id: FacilityId,
      val mortalityRate: Int,
      val name: String,
      override val notes: String?,
      override val operationStartedDate: LocalDate?,
      val operationStartedDateEditable: Boolean,
      override val selected: Boolean,
      val totalPlantsPropagated: Long,
      val totalPlantsPropagatedForProject: Long?,
      override val workers: WorkersPayloadV1,
  ) : EditableReportFieldsV1.Nursery {
    constructor(
        model: SeedFundReportBodyModelV1.Nursery
    ) : this(
        buildCompletedDate = model.buildCompletedDate,
        buildCompletedDateEditable = model.buildCompletedDateEditable,
        buildStartedDate = model.buildStartedDate,
        buildStartedDateEditable = model.buildStartedDateEditable,
        capacity = model.capacity,
        id = model.id,
        mortalityRate = model.mortalityRate,
        name = model.name,
        notes = model.notes,
        operationStartedDate = model.operationStartedDate,
        operationStartedDateEditable = model.operationStartedDateEditable,
        selected = model.selected,
        totalPlantsPropagated = model.totalPlantsPropagated,
        totalPlantsPropagatedForProject = model.totalPlantsPropagatedForProject,
        workers = WorkersPayloadV1(model.workers),
    )
  }

  data class GetPlantingSiteV1(
      override val id: PlantingSiteId,
      override val mortalityRate: Int?,
      val name: String,
      override val notes: String?,
      override val selected: Boolean,
      override val species: List<GetPlantingSiteSpeciesV1>,
      override val totalPlantedArea: Int?,
      override val totalPlantingSiteArea: Int?,
      override val totalPlantsPlanted: Int?,
      override val totalTreesPlanted: Int?,
      override val workers: WorkersPayloadV1,
  ) : EditableReportFieldsV1.PlantingSite {
    constructor(
        model: SeedFundReportBodyModelV1.PlantingSite
    ) : this(
        id = model.id,
        mortalityRate = model.mortalityRate,
        name = model.name,
        notes = model.notes,
        selected = model.selected,
        species = model.species.map { GetPlantingSiteSpeciesV1(it) },
        totalPlantedArea = model.totalPlantedArea,
        totalPlantingSiteArea = model.totalPlantingSiteArea,
        totalPlantsPlanted = model.totalPlantsPlanted,
        totalTreesPlanted = model.totalTreesPlanted,
        workers = WorkersPayloadV1(model.workers),
    )

    data class GetPlantingSiteSpeciesV1(
        override val id: SpeciesId,
        override val mortalityRateInField: Int?,
        override val totalPlanted: Int?,
    ) : EditableReportFieldsV1.PlantingSite.Species {
      constructor(
          model: SeedFundReportBodyModelV1.PlantingSite.Species
      ) : this(
          id = model.id,
          mortalityRateInField = model.mortalityRateInField,
          totalPlanted = model.totalPlanted,
      )
    }
  }

  data class GetSeedBankV1(
      override val buildCompletedDate: LocalDate?,
      val buildCompletedDateEditable: Boolean,
      override val buildStartedDate: LocalDate?,
      val buildStartedDateEditable: Boolean,
      override val id: FacilityId,
      val name: String,
      override val notes: String?,
      override val operationStartedDate: LocalDate?,
      val operationStartedDateEditable: Boolean,
      override val selected: Boolean,
      val totalSeedsStored: Long,
      val totalSeedsStoredForProject: Long?,
      override val workers: WorkersPayloadV1,
  ) : EditableReportFieldsV1.SeedBank {
    constructor(
        model: SeedFundReportBodyModelV1.SeedBank
    ) : this(
        buildCompletedDate = model.buildCompletedDate,
        buildCompletedDateEditable = model.buildCompletedDateEditable,
        buildStartedDate = model.buildStartedDate,
        buildStartedDateEditable = model.buildStartedDateEditable,
        id = model.id,
        name = model.name,
        notes = model.notes,
        operationStartedDate = model.operationStartedDate,
        operationStartedDateEditable = model.operationStartedDateEditable,
        selected = model.selected,
        totalSeedsStored = model.totalSeedsStored,
        totalSeedsStoredForProject = model.totalSeedsStoredForProject,
        workers = WorkersPayloadV1(model.workers),
    )
  }
}

@JsonTypeName("1")
data class PutReportPayloadV1(
    override val annualDetails: AnnualDetailsPayloadV1?,
    override val notes: String?,
    override val nurseries: List<PutNurseryV1>,
    override val plantingSites: List<PutPlantingSiteV1>,
    override val seedBanks: List<PutSeedBankV1>,
    override val summaryOfProgress: String?,
) : EditableReportFieldsV1, PutReportPayload {
  override fun copyTo(model: LatestSeedFundReportBodyModel) =
      model.copy(
          annualDetails = annualDetails?.toModel(),
          notes = notes,
          nurseries =
              model.nurseries.map { nursery ->
                nurseries.find { it.id == nursery.id }?.copyTo(nursery) ?: nursery
              },
          plantingSites =
              model.plantingSites.map { plantingSite ->
                plantingSites.find { it.id == plantingSite.id }?.copyTo(plantingSite)
                    ?: plantingSite
              },
          seedBanks =
              model.seedBanks.map { seedBank ->
                seedBanks.find { it.id == seedBank.id }?.copyTo(seedBank) ?: seedBank
              },
          summaryOfProgress = summaryOfProgress,
      )

  data class PutNurseryV1(
      override val buildCompletedDate: LocalDate?,
      override val buildStartedDate: LocalDate?,
      override val capacity: Int?,
      override val id: FacilityId,
      override val notes: String?,
      override val operationStartedDate: LocalDate?,
      override val selected: Boolean,
      override val workers: WorkersPayloadV1,
  ) : EditableReportFieldsV1.Nursery {
    fun copyTo(model: SeedFundReportBodyModelV1.Nursery) =
        model.copy(
            buildCompletedDate = buildCompletedDate,
            buildStartedDate = buildStartedDate,
            capacity = capacity,
            notes = notes,
            operationStartedDate = operationStartedDate,
            selected = selected,
            workers = workers.toModel(),
        )
  }

  data class PutPlantingSiteV1(
      override val id: PlantingSiteId,
      override val notes: String?,
      override val selected: Boolean,
      override val species: List<PutPlantingSiteSpeciesV1>,
      override val mortalityRate: Int?,
      override val totalPlantedArea: Int?,
      override val totalPlantingSiteArea: Int?,
      override val totalPlantsPlanted: Int?,
      override val totalTreesPlanted: Int?,
      override val workers: WorkersPayloadV1,
  ) : EditableReportFieldsV1.PlantingSite {
    fun copyTo(model: SeedFundReportBodyModelV1.PlantingSite) =
        model.copy(
            mortalityRate = mortalityRate,
            selected = selected,
            species =
                model.species.map { speciesModel ->
                  species.find { it.id == speciesModel.id }?.copyTo(speciesModel) ?: speciesModel
                },
            totalPlantedArea = totalPlantedArea,
            totalPlantingSiteArea = totalPlantingSiteArea,
            totalPlantsPlanted = totalPlantsPlanted,
            totalTreesPlanted = totalTreesPlanted,
            workers = workers.toModel(),
        )

    data class PutPlantingSiteSpeciesV1(
        override val id: SpeciesId,
        override val mortalityRateInField: Int?,
        override val totalPlanted: Int?,
    ) : EditableReportFieldsV1.PlantingSite.Species {
      fun copyTo(model: SeedFundReportBodyModelV1.PlantingSite.Species) =
          model.copy(
              mortalityRateInField = mortalityRateInField,
              totalPlanted = totalPlanted,
          )
    }
  }

  data class PutSeedBankV1(
      override val buildCompletedDate: LocalDate?,
      override val buildStartedDate: LocalDate?,
      override val id: FacilityId,
      override val notes: String?,
      override val operationStartedDate: LocalDate?,
      override val selected: Boolean,
      override val workers: WorkersPayloadV1,
  ) : EditableReportFieldsV1.SeedBank {
    fun copyTo(model: SeedFundReportBodyModelV1.SeedBank) =
        model.copy(
            buildCompletedDate = buildCompletedDate,
            buildStartedDate = buildStartedDate,
            notes = notes,
            operationStartedDate = operationStartedDate,
            selected = selected,
            workers = workers.toModel(),
        )
  }
}

data class WorkersPayloadV1(
    override val femalePaidWorkers: Int?,
    override val paidWorkers: Int?,
    override val volunteers: Int?,
) : EditableReportFieldsV1.Workers {
  constructor(
      model: SeedFundReportBodyModelV1.Workers
  ) : this(
      femalePaidWorkers = model.femalePaidWorkers,
      paidWorkers = model.paidWorkers,
      volunteers = model.volunteers,
  )

  fun toModel() =
      SeedFundReportBodyModelV1.Workers(
          femalePaidWorkers = femalePaidWorkers,
          paidWorkers = paidWorkers,
          volunteers = volunteers,
      )
}

data class AnnualDetailsPayloadV1(
    @ArraySchema(schema = Schema(minimum = "1", maximum = "12"))
    override val bestMonthsForObservation: Set<Int>,
    override val budgetNarrativeSummary: String?,
    override val catalyticDetail: String?,
    override val challenges: String?,
    override val isCatalytic: Boolean,
    override val keyLessons: String?,
    override val nextSteps: String?,
    override val opportunities: String?,
    override val projectImpact: String?,
    override val projectSummary: String?,
    override val socialImpact: String?,
    override val successStories: String?,
    override val sustainableDevelopmentGoals: List<GoalProgressPayloadV1>,
) : EditableReportFieldsV1.AnnualDetails {
  constructor(
      model: SeedFundReportBodyModelV1.AnnualDetails
  ) : this(
      bestMonthsForObservation = model.bestMonthsForObservation,
      budgetNarrativeSummary = model.budgetNarrativeSummary,
      catalyticDetail = model.catalyticDetail,
      challenges = model.challenges,
      isCatalytic = model.isCatalytic,
      keyLessons = model.keyLessons,
      nextSteps = model.nextSteps,
      opportunities = model.opportunities,
      projectImpact = model.projectImpact,
      projectSummary = model.projectSummary,
      socialImpact = model.socialImpact,
      successStories = model.successStories,
      sustainableDevelopmentGoals =
          model.sustainableDevelopmentGoals.map { GoalProgressPayloadV1(it) },
  )

  fun toModel() =
      SeedFundReportBodyModelV1.AnnualDetails(
          bestMonthsForObservation = bestMonthsForObservation,
          budgetNarrativeSummary = budgetNarrativeSummary,
          catalyticDetail = catalyticDetail,
          challenges = challenges,
          isCatalytic = isCatalytic,
          keyLessons = keyLessons,
          nextSteps = nextSteps,
          opportunities = opportunities,
          projectImpact = projectImpact,
          projectSummary = projectSummary,
          socialImpact = socialImpact,
          successStories = successStories,
          sustainableDevelopmentGoals = sustainableDevelopmentGoals.map { it.toModel() },
      )

  data class GoalProgressPayloadV1(
      override val goal: SustainableDevelopmentGoal,
      override val progress: String?,
  ) : EditableReportFieldsV1.AnnualDetails.GoalProgress {
    constructor(
        model: SeedFundReportBodyModelV1.AnnualDetails.GoalProgress
    ) : this(
        goal = model.goal,
        progress = model.progress,
    )

    fun toModel() = SeedFundReportBodyModelV1.AnnualDetails.GoalProgress(goal, progress)
  }
}
