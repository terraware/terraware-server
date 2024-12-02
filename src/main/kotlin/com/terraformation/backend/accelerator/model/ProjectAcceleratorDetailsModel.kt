package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import java.math.BigDecimal
import java.net.URI
import org.jooq.Record

data class ProjectAcceleratorDetailsModel(
    val annualCarbon: BigDecimal? = null,
    val applicationReforestableLand: BigDecimal? = null,
    val carbonCapacity: BigDecimal? = null,
    val cohortId: CohortId? = null,
    val cohortName: String? = null,
    val cohortPhase: CohortPhase? = null,
    val confirmedReforestableLand: BigDecimal? = null,
    val countryCode: String? = null,
    val dealDescription: String? = null,
    val dealName: String? = null,
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
    val participantId: ParticipantId? = null,
    val participantName: String? = null,
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
    /** Combining variables and table values. Unused columns will be deprecated going forward. */
    fun of(
        record: Record,
        variableValues: ProjectAcceleratorVariableValuesModel,
    ): ProjectAcceleratorDetailsModel {
      return with(PROJECT_ACCELERATOR_DETAILS) {
        ProjectAcceleratorDetailsModel(
            annualCarbon = variableValues.annualCarbon,
            applicationReforestableLand = variableValues.applicationReforestableLand,
            carbonCapacity = variableValues.carbonCapacity,
            cohortId = record[COHORTS.ID],
            cohortName = record[COHORTS.NAME],
            cohortPhase = record[COHORTS.PHASE_ID],
            confirmedReforestableLand = variableValues.confirmedReforestableLand,
            countryCode = variableValues.countryCode,
            dealDescription = variableValues.dealDescription,
            dealName = variableValues.dealName,
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
            participantId = record[PARTICIPANTS.ID],
            participantName = record[PARTICIPANTS.NAME],
            perHectareBudget = variableValues.perHectareBudget,
            pipeline = record[PIPELINE_ID],
            projectId = record[PROJECTS.ID]!!,
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
          dealName,
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
