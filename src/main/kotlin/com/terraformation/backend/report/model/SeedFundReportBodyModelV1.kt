package com.terraformation.backend.report.model

import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.nursery.model.NurseryStats
import com.terraformation.backend.report.SeedFundReportNotCompleteException
import com.terraformation.backend.seedbank.model.AccessionSummaryStatistics
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import java.time.LocalDate

@JsonTypeName("1")
data class SeedFundReportBodyModelV1(
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
) : SeedFundReportBodyModel {
  override fun toLatestVersion() = this

  override fun validate() {
    nurseries.filter { it.selected }.forEach { it.validate() }
    plantingSites.filter { it.selected }.forEach { it.validate() }
    seedBanks.filter { it.selected }.forEach { it.validate() }

    if (isAnnual) {
      annualDetails?.validate()
          ?: throw SeedFundReportNotCompleteException("Missing annual report details")
    }
  }

  data class Nursery(
      val buildCompletedDate: LocalDate? = null,
      val buildCompletedDateEditable: Boolean = true,
      val buildStartedDate: LocalDate? = null,
      val buildStartedDateEditable: Boolean = true,
      val capacity: Int? = null,
      val id: FacilityId,
      val mortalityRate: Int,
      val name: String,
      val notes: String? = null,
      val operationStartedDate: LocalDate? = null,
      val operationStartedDateEditable: Boolean = true,
      val selected: Boolean = true,
      val totalPlantsPropagated: Long,
      val totalPlantsPropagatedForProject: Long? = null,
      val workers: Workers = Workers(),
  ) {
    constructor(
        model: FacilityModel,
        orgStats: NurseryStats,
        projectStats: NurseryStats?,
    ) : this(
        buildCompletedDate = model.buildCompletedDate,
        buildCompletedDateEditable = model.buildCompletedDate == null,
        buildStartedDate = model.buildStartedDate,
        buildStartedDateEditable = model.buildStartedDate == null,
        capacity = model.capacity,
        id = model.id,
        mortalityRate = orgStats.mortalityRate,
        name = model.name,
        operationStartedDate = model.operationStartedDate,
        operationStartedDateEditable = model.operationStartedDate == null,
        totalPlantsPropagated = orgStats.totalPlantsPropagated,
        totalPlantsPropagatedForProject = projectStats?.totalPlantsPropagated,
    )

    fun populate(
        model: FacilityModel,
        orgStats: NurseryStats,
        projectStats: NurseryStats?,
    ): Nursery {
      return copy(
          buildCompletedDate = model.buildCompletedDate ?: buildCompletedDate,
          buildCompletedDateEditable = model.buildCompletedDate == null,
          buildStartedDate = model.buildStartedDate ?: buildStartedDate,
          buildStartedDateEditable = model.buildStartedDate == null,
          capacity = model.capacity ?: capacity,
          mortalityRate = orgStats.mortalityRate,
          name = model.name,
          operationStartedDate = model.operationStartedDate ?: operationStartedDate,
          operationStartedDateEditable = model.operationStartedDate == null,
          totalPlantsPropagated = orgStats.totalPlantsPropagated,
          totalPlantsPropagatedForProject = projectStats?.totalPlantsPropagated,
      )
    }

    internal fun validate() {
      Validator("Nursery $id").use {
        failIfNull(buildCompletedDate, "build completed date")
        failIfNull(buildStartedDate, "build started date")
        failIfNull(capacity, "capacity")
        failIfNull(operationStartedDate, "operation started date")

        workers.validate(this@use)
      }
    }
  }

  data class PlantingSite(
      val id: PlantingSiteId,
      val mortalityRate: Int? = null,
      val name: String,
      val notes: String? = null,
      val selected: Boolean = true,
      val species: List<Species> = emptyList(),
      val totalPlantedArea: Int? = null,
      val totalPlantingSiteArea: Int? = null,
      val totalPlantsPlanted: Int? = null,
      val totalTreesPlanted: Int? = null,
      val workers: Workers = Workers(),
  ) {
    constructor(
        model: ExistingPlantingSiteModel,
        species: List<ExistingSpeciesModel>,
    ) : this(
        id = model.id,
        name = model.name,
        species = species.map { Species(it) },
    )

    fun populate(
        model: ExistingPlantingSiteModel,
        speciesModels: List<ExistingSpeciesModel>,
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

    internal fun validate() {
      Validator("Planting site $id").use {
        failIfNull(mortalityRate, "mortality rate")
        failIfNull(totalPlantedArea, "total planted area")
        failIfNull(totalPlantingSiteArea, "total planting site area")
        failIfNull(totalPlantsPlanted, "total plants planted")
        failIfNull(totalTreesPlanted, "total trees planted")

        species.forEach { it.validate(this@use) }
        workers.validate(this@use)
      }
    }

    data class Species(
        val growthForms: Set<GrowthForm> = emptySet(),
        val id: SpeciesId,
        val mortalityRateInField: Int? = null,
        val scientificName: String,
        val totalPlanted: Int? = null,
    ) {
      constructor(
          model: ExistingSpeciesModel
      ) : this(
          growthForms = model.growthForms,
          id = model.id,
          scientificName = model.scientificName,
      )

      fun freshen(model: ExistingSpeciesModel): Species {
        return copy(growthForms = model.growthForms, scientificName = model.scientificName)
      }

      internal fun validate(context: Validator) {
        context.use("species $id") {
          failIfNull(mortalityRateInField, "mortality rate in field")
          failIfNull(totalPlanted, "total planted")
        }
      }
    }
  }

  data class SeedBank(
      val buildCompletedDate: LocalDate? = null,
      val buildCompletedDateEditable: Boolean = true,
      val buildStartedDate: LocalDate? = null,
      val buildStartedDateEditable: Boolean = true,
      val id: FacilityId,
      val name: String,
      val notes: String? = null,
      val operationStartedDate: LocalDate? = null,
      val operationStartedDateEditable: Boolean = true,
      val selected: Boolean = true,
      val totalSeedsStored: Long = 0,
      val totalSeedsStoredForProject: Long? = null,
      val workers: Workers = Workers(),
  ) {
    constructor(
        model: FacilityModel,
        orgStats: AccessionSummaryStatistics,
        projectStats: AccessionSummaryStatistics?,
    ) : this(
        buildCompletedDate = model.buildCompletedDate,
        buildCompletedDateEditable = model.buildCompletedDate == null,
        buildStartedDate = model.buildStartedDate,
        buildStartedDateEditable = model.buildStartedDate == null,
        id = model.id,
        name = model.name,
        operationStartedDate = model.operationStartedDate,
        operationStartedDateEditable = model.operationStartedDate == null,
        totalSeedsStored = orgStats.totalSeedsStored,
        totalSeedsStoredForProject = projectStats?.totalSeedsStored,
    )

    fun populate(
        model: FacilityModel,
        orgStats: AccessionSummaryStatistics,
        projectStats: AccessionSummaryStatistics?,
    ): SeedBank {
      return copy(
          buildCompletedDate = model.buildCompletedDate ?: buildCompletedDate,
          buildCompletedDateEditable = model.buildCompletedDate == null,
          buildStartedDate = model.buildStartedDate ?: buildStartedDate,
          buildStartedDateEditable = model.buildStartedDate == null,
          name = model.name,
          operationStartedDate = model.operationStartedDate ?: operationStartedDate,
          operationStartedDateEditable = model.operationStartedDate == null,
          totalSeedsStored = orgStats.totalSeedsStored,
          totalSeedsStoredForProject = projectStats?.totalSeedsStored,
      )
    }

    internal fun validate() {
      Validator("Seed bank $id").use {
        failIfNull(buildCompletedDate, "build completed date")
        failIfNull(buildStartedDate, "build started date")
        failIfNull(operationStartedDate, "operation started date")

        workers.validate(this@use)
      }
    }
  }

  data class Workers(
      val femalePaidWorkers: Int? = null,
      val paidWorkers: Int? = null,
      val volunteers: Int? = null,
  ) {
    internal fun validate(validator: Validator) {
      validator.use {
        failIfNull(femalePaidWorkers, "female paid workers")
        failIfNull(paidWorkers, "paid workers")
        failIfNull(volunteers, "volunteers")
      }
    }
  }

  data class AnnualDetails(
      val bestMonthsForObservation: Set<Int> = emptySet(),
      val budgetNarrativeSummary: String? = null,
      val catalyticDetail: String? = null,
      val challenges: String? = null,
      val isCatalytic: Boolean = false,
      val keyLessons: String? = null,
      val nextSteps: String? = null,
      val opportunities: String? = null,
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

    internal fun validate() {
      Validator("Annual details").use {
        failIf(bestMonthsForObservation.isEmpty(), "missing best months for observation")
        failIfNull(budgetNarrativeSummary, "budget narrative summary")
        failIf(isCatalytic && catalyticDetail == null, "missing catalytic detail")
        failIfNull(challenges, "challenges")
        failIfNull(keyLessons, "key lessons")
        failIfNull(nextSteps, "next steps")
        failIfNull(opportunities, "opportunities")
        failIfNull(projectImpact, "project impact")
        failIfNull(projectSummary, "project summary")
        failIfNull(socialImpact, "social impact")
        failIfNull(successStories, "success stories")
      }
    }
  }

  internal class Validator(val context: String) {
    fun failIf(condition: Boolean, message: String) {
      if (condition) {
        throw SeedFundReportNotCompleteException("$context $message")
      }
    }

    fun failIfNull(value: Any?, name: String) {
      failIf(value == null, "missing $name")
    }

    fun use(func: Validator.() -> Unit) {
      this.func()
    }

    fun use(name: String, func: Validator.() -> Unit) {
      Validator("$context $name").use(func)
    }
  }
}
