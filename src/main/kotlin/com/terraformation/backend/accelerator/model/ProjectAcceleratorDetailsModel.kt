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
import java.net.URI
import org.jooq.Field
import org.jooq.Record

data class ProjectAcceleratorDetailsModel(
    val annualCarbon: BigDecimal? = null,
    val applicationReforestableLand: BigDecimal? = null,
    val carbonCapacity: BigDecimal? = null,
    val confirmedReforestableLand: BigDecimal? = null,
    val countryCode: String? = null,
    val dealDescription: String? = null,
    val dealStage: DealStage? = null,
    val dropboxFolderPath: String? = null,
    val failureRisk: String? = null,
    val fileNaming: String? = null,
    val googleFolderUrl: URI? = null,
    val hubSpotUrl: URI? = null,
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
    val totalCarbon: BigDecimal? = null,
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
            annualCarbon = record[ANNUAL_CARBON],
            applicationReforestableLand = record[APPLICATION_REFORESTABLE_LAND],
            carbonCapacity = record[CARBON_CAPACITY],
            confirmedReforestableLand = record[CONFIRMED_REFORESTABLE_LAND],
            countryCode = record[PROJECTS.COUNTRY_CODE],
            dealDescription = record[DEAL_DESCRIPTION],
            dealStage = record[DEAL_STAGE_ID],
            dropboxFolderPath = record[DROPBOX_FOLDER_PATH],
            failureRisk = record[FAILURE_RISK],
            fileNaming = record[FILE_NAMING],
            googleFolderUrl = record[GOOGLE_FOLDER_URL],
            hubSpotUrl = record[HUBSPOT_URL],
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
            totalCarbon = record[TOTAL_CARBON],
            totalExpansionPotential = record[TOTAL_EXPANSION_POTENTIAL],
            whatNeedsToBeTrue = record[WHAT_NEEDS_TO_BE_TRUE],
        )
      }
    }

    /** Combining variables and table values. Unused columns will be deprecated going forward. */
    fun of(
        record: Record,
        variableValues: ProjectAcceleratorVariableValuesModel
    ): ProjectAcceleratorDetailsModel {
      return with(PROJECT_ACCELERATOR_DETAILS) {
        ProjectAcceleratorDetailsModel(
            annualCarbon = variableValues.annualCarbon,
            applicationReforestableLand = variableValues.applicationReforestableLand,
            carbonCapacity = variableValues.carbonCapacity,
            confirmedReforestableLand = variableValues.confirmedReforestableLand,
            countryCode = variableValues.countryCode,
            dealDescription = variableValues.dealDescription,
            dealStage = record[DEAL_STAGE_ID],
            dropboxFolderPath = record[DROPBOX_FOLDER_PATH],
            failureRisk = variableValues.failureRisk,
            fileNaming = record[FILE_NAMING],
            googleFolderUrl = record[GOOGLE_FOLDER_URL],
            hubSpotUrl = record[HUBSPOT_URL],
            investmentThesis = variableValues.investmentThesis,
            landUseModelTypes = variableValues.landUseModelTypes,
            maxCarbonAccumulation = variableValues.maxCarbonAccumulation,
            minCarbonAccumulation = variableValues.minCarbonAccumulation,
            numCommunities = record[NUM_COMMUNITIES],
            numNativeSpecies = variableValues.numNativeSpecies,
            perHectareBudget = variableValues.perHectareBudget,
            pipeline = record[PIPELINE_ID],
            projectId = variableValues.projectId,
            projectLead = record[PROJECT_LEAD],
            region = variableValues.region,
            totalCarbon = variableValues.totalCarbon,
            totalExpansionPotential = variableValues.totalExpansionPotential,
            whatNeedsToBeTrue = variableValues.whatNeedsToBeTrue,
        )
      }
    }
  }

  fun toVariableValuesModel() =
      ProjectAcceleratorVariableValuesModel(
          annualCarbon,
          applicationReforestableLand,
          carbonCapacity,
          confirmedReforestableLand,
          countryCode,
          dealDescription,
          failureRisk,
          investmentThesis,
          landUseModelTypes,
          maxCarbonAccumulation,
          minCarbonAccumulation,
          numNativeSpecies,
          perHectareBudget,
          projectId,
          region,
          totalCarbon,
          totalExpansionPotential,
          whatNeedsToBeTrue,
      )
}
