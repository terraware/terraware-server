package com.terraformation.backend.report.model

import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import java.time.LocalDate

@JsonTypeName("1")
data class ReportBodyModelV1(
    val annualDetails: AnnualDetails? = null,
    val isAnnual: Boolean = false,
    val notes: String? = null,
    val nurseries: List<Nursery> = emptyList(),
    val organizationName: String,
    val plantingSites: List<PlantingSite> = emptyList(),
    val seedBanks: List<SeedBank> = emptyList(),
    val summaryOfProgress: String? = null,
    val totalNurseries: Int = 0,
    val totalPlantingSites: Int = 0,
    val totalSeedBanks: Int = 0,
) : ReportBodyModel {
  override fun toLatestVersion() = this

  data class Nursery(
      val buildCompletedDate: LocalDate?,
      val buildCompletedDateEditable: Boolean,
      val buildStartedDate: LocalDate?,
      val buildStartedDateEditable: Boolean,
      val capacity: Int?,
      val id: FacilityId,
      val name: String,
      val notes: String? = null,
      val operationStartedDate: LocalDate?,
      val operationStartedDateEditable: Boolean,
      val selected: Boolean,
      val workers: Workers,
  ) {
    constructor(
        model: FacilityModel
    ) : this(
        buildCompletedDate = model.buildCompletedDate,
        buildCompletedDateEditable = model.buildCompletedDate == null,
        buildStartedDate = model.buildStartedDate,
        buildStartedDateEditable = model.buildStartedDate == null,
        capacity = model.capacity,
        id = model.id,
        name = model.name,
        operationStartedDate = model.operationStartedDate,
        operationStartedDateEditable = model.operationStartedDate == null,
        selected = true,
        workers = Workers(),
    )

    fun populate(model: FacilityModel): Nursery {
      return copy(
          buildCompletedDate = model.buildCompletedDate ?: buildCompletedDate,
          buildCompletedDateEditable = model.buildCompletedDate == null,
          buildStartedDate = model.buildStartedDate ?: buildStartedDate,
          buildStartedDateEditable = model.buildStartedDate == null,
          capacity = model.capacity ?: capacity,
          name = model.name,
          operationStartedDate = model.operationStartedDate ?: operationStartedDate,
          operationStartedDateEditable = model.operationStartedDate == null,
      )
    }
  }

  data class PlantingSite(
      val id: PlantingSiteId,
      val mortalityRate: Int? = null,
      val name: String,
      val selected: Boolean,
      val species: List<Species>,
      val totalPlantedArea: Int? = null,
      val totalPlantingSiteArea: Int? = null,
      val totalPlantsPlanted: Int? = null,
      val totalTreesPlanted: Int? = null,
      val workers: Workers,
  ) {
    constructor(
        model: PlantingSiteModel,
        species: List<ExistingSpeciesModel>
    ) : this(
        id = model.id,
        name = model.name,
        selected = true,
        species = species.map { Species(it) },
        workers = Workers(),
    )

    fun populate(
        model: PlantingSiteModel,
        speciesModels: List<ExistingSpeciesModel>
    ): PlantingSite {
      return copy(
          name = model.name,
          species =
              speciesModels.map { speciesModel ->
                species.find { it.id == speciesModel.id }?.freshen(speciesModel)
                    ?: Species(speciesModel)
              },
      )
    }

    data class Species(
        val growthForm: GrowthForm?,
        val id: SpeciesId,
        val mortalityRateInField: Int? = null,
        val mortalityRateInNursery: Int? = null,
        val scientificName: String,
        val totalPlanted: Int? = null,
    ) {
      constructor(
          model: ExistingSpeciesModel
      ) : this(
          growthForm = model.growthForm,
          id = model.id,
          scientificName = model.scientificName,
      )

      fun freshen(model: ExistingSpeciesModel): Species {
        return copy(growthForm = model.growthForm, scientificName = model.scientificName)
      }
    }
  }

  data class SeedBank(
      val buildCompletedDate: LocalDate?,
      val buildCompletedDateEditable: Boolean,
      val buildStartedDate: LocalDate?,
      val buildStartedDateEditable: Boolean,
      val id: FacilityId,
      val name: String,
      val notes: String? = null,
      val operationStartedDate: LocalDate?,
      val operationStartedDateEditable: Boolean,
      val totalSeedsStored: Long,
      val workers: Workers,
  ) {
    constructor(
        model: FacilityModel,
        totalSeedsStored: Long
    ) : this(
        buildCompletedDate = model.buildCompletedDate,
        buildCompletedDateEditable = model.buildCompletedDate == null,
        buildStartedDate = model.buildStartedDate,
        buildStartedDateEditable = model.buildStartedDate == null,
        id = model.id,
        name = model.name,
        notes = null,
        operationStartedDate = model.operationStartedDate,
        operationStartedDateEditable = model.operationStartedDate == null,
        totalSeedsStored = totalSeedsStored,
        workers = Workers(),
    )

    fun populate(model: FacilityModel, totalSeedsStored: Long): SeedBank {
      return copy(
          buildCompletedDate = model.buildCompletedDate ?: buildCompletedDate,
          buildCompletedDateEditable = model.buildCompletedDate == null,
          buildStartedDate = model.buildStartedDate ?: buildStartedDate,
          buildStartedDateEditable = model.buildStartedDate == null,
          name = model.name,
          operationStartedDate = model.operationStartedDate ?: operationStartedDate,
          operationStartedDateEditable = model.operationStartedDate == null,
          totalSeedsStored = totalSeedsStored,
      )
    }
  }

  data class Workers(
      val femalePaidWorkers: Int? = null,
      val paidWorkers: Int? = null,
      val volunteers: Int? = null,
  )

  data class AnnualDetails(
      val bestMonthsForObservation: Set<Int> = emptySet(),
      val budgetNarrativeSummary: String? = null,
      val catalyticDetail: String? = null,
      val challenges: String? = null,
      val isCatalytic: Boolean = false,
      val keyLessons: String? = null,
      val nextSteps: String? = null,
      val projectImpact: String? = null,
      val projectSummary: String? = null,
      val socialImpact: String? = null,
      val successStories: String? = null,
      val sustainableDevelopmentGoals: List<GoalProgress> = emptyList(),
  ) {
    data class GoalProgress(
        val goal: SustainableDevelopmentGoal,
        val progress: String? = null,
    )
  }
}
