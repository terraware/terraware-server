package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import java.math.BigDecimal
import org.jooq.Field
import org.jooq.Record

data class ProjectAcceleratorDetailsModel(
    val applicationReforestableLand: BigDecimal? = null,
    val confirmedReforestableLand: BigDecimal? = null,
    val countryCode: String? = null,
    val dealDescription: String? = null,
    val dealStage: DealStage? = null,
    val failureRisk: String? = null,
    val investmentThesis: String? = null,
    val landUseModelTypes: Set<LandUseModelType> = emptySet(),
    val maxCarbonAccumulation: BigDecimal? = null,
    val minCarbonAccumulation: BigDecimal? = null,
    val numCommunities: Int? = null,
    val numNativeSpecies: Int? = null,
    val perHectareBudget: BigDecimal? = null,
    val pipeline: Pipeline? = null,
    val projectId: ProjectId,
    val projectLead: String? = null,
    val region: Region? = null,
    val totalExpansionPotential: BigDecimal? = null,
    val whatNeedsToBeTrue: String? = null,
) {
  companion object {
    fun of(
        record: Record,
        landUseModelTypesMultiset: Field<Set<LandUseModelType>>
    ): ProjectAcceleratorDetailsModel {
      return with(PROJECT_ACCELERATOR_DETAILS) {
        ProjectAcceleratorDetailsModel(
            applicationReforestableLand = record[APPLICATION_REFORESTABLE_LAND],
            confirmedReforestableLand = record[CONFIRMED_REFORESTABLE_LAND],
            countryCode = record[PROJECTS.COUNTRY_CODE],
            dealDescription = record[DEAL_DESCRIPTION],
            dealStage = record[DEAL_STAGE_ID],
            failureRisk = record[FAILURE_RISK],
            investmentThesis = record[INVESTMENT_THESIS],
            landUseModelTypes = record[landUseModelTypesMultiset]?.toSet() ?: emptySet(),
            maxCarbonAccumulation = record[MAX_CARBON_ACCUMULATION],
            minCarbonAccumulation = record[MIN_CARBON_ACCUMULATION],
            numCommunities = record[NUM_COMMUNITIES],
            numNativeSpecies = record[NUM_NATIVE_SPECIES],
            perHectareBudget = record[PER_HECTARE_BUDGET],
            pipeline = record[PIPELINE_ID],
            projectId = record[PROJECTS.ID]!!,
            projectLead = record[PROJECT_LEAD],
            region = record[COUNTRIES.REGION_ID],
            totalExpansionPotential = record[TOTAL_EXPANSION_POTENTIAL],
            whatNeedsToBeTrue = record[WHAT_NEEDS_TO_BE_TRUE],
        )
      }
    }
  }
}
