package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
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
import com.terraformation.backend.db.docprod.VariableValueId
import java.math.BigDecimal
import java.net.URI
import org.jooq.Record

data class ProjectAcceleratorDetailsModel(
    val accumulationRate: BigDecimal? = null,
    val annualCarbon: BigDecimal? = null,
    val applicationId: ApplicationId? = null,
    val applicationStatus: ApplicationStatus? = null,
    val applicationReforestableLand: BigDecimal? = null,
    val carbonCapacity: BigDecimal? = null,
    val clickUpLink: URI? = null,
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
    val gisReportsLink: URI? = null,
    val fileNaming: String? = null,
    val googleFolderUrl: URI? = null,
    val hubSpotUrl: URI? = null,
    val investmentThesis: String? = null,
    val landUseModelTypes: Set<LandUseModelType> = emptySet(),
    val landUseModelHectares: Map<LandUseModelType, BigDecimal> = emptyMap(),
    val maxCarbonAccumulation: BigDecimal? = null,
    val methodologyNumber: String? = null,
    val minCarbonAccumulation: BigDecimal? = null,
    val minProjectArea: BigDecimal? = null,
    val numCommunities: Int? = null,
    val numNativeSpecies: Int? = null,
    val participantId: ParticipantId? = null,
    val participantName: String? = null,
    val perHectareBudget: BigDecimal? = null,
    val pipeline: Pipeline? = null,
    val projectArea: BigDecimal? = null,
    val projectHighlightPhotoValueId: VariableValueId? = null,
    val projectId: ProjectId,
    val projectLead: String? = null,
    val projectZoneFigureValueId: VariableValueId? = null,
    val region: Region? = null,
    val riskTrackerLink: URI? = null,
    val sdgList: Set<SustainableDevelopmentGoal> = emptySet(),
    val slackLink: URI? = null,
    val standard: String? = null,
    val totalCarbon: BigDecimal? = null,
    val totalExpansionPotential: BigDecimal? = null,
    val totalVCU: BigDecimal? = null,
    val verraLink: URI? = null,
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
            accumulationRate = variableValues.accumulationRate,
            annualCarbon = variableValues.annualCarbon,
            applicationReforestableLand = variableValues.applicationReforestableLand,
            carbonCapacity = variableValues.carbonCapacity,
            clickUpLink = variableValues.clickUpLink,
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
            gisReportsLink = variableValues.gisReportsLink,
            googleFolderUrl = record[GOOGLE_FOLDER_URL],
            hubSpotUrl = record[HUBSPOT_URL],
            investmentThesis = variableValues.investmentThesis,
            landUseModelTypes = variableValues.landUseModelTypes,
            landUseModelHectares = variableValues.landUseModelHectares,
            maxCarbonAccumulation = variableValues.maxCarbonAccumulation,
            methodologyNumber = variableValues.methodologyNumber,
            minCarbonAccumulation = variableValues.minCarbonAccumulation,
            minProjectArea = variableValues.minProjectArea,
            numCommunities = record[NUM_COMMUNITIES],
            numNativeSpecies = variableValues.numNativeSpecies,
            participantId = record[PARTICIPANTS.ID],
            participantName = record[PARTICIPANTS.NAME],
            perHectareBudget = variableValues.perHectareBudget,
            pipeline = record[PIPELINE_ID],
            projectArea = variableValues.projectArea,
            projectHighlightPhotoValueId = variableValues.projectHighlightPhotoValueId,
            projectId = record[PROJECTS.ID]!!,
            projectLead = record[PROJECT_LEAD],
            projectZoneFigureValueId = variableValues.projectZoneFigureValueId,
            region = variableValues.region,
            riskTrackerLink = variableValues.riskTrackerLink,
            sdgList = variableValues.sdgList,
            slackLink = variableValues.slackLink,
            standard = variableValues.standard,
            totalCarbon = variableValues.totalCarbon,
            totalExpansionPotential = variableValues.totalExpansionPotential,
            totalVCU = variableValues.totalVCU,
            verraLink = variableValues.verraLink,
            whatNeedsToBeTrue = variableValues.whatNeedsToBeTrue,
        )
      }
    }
  }

  fun toVariableValuesModel() =
      ProjectAcceleratorVariableValuesModel(
          accumulationRate = accumulationRate,
          annualCarbon = annualCarbon,
          applicationReforestableLand = applicationReforestableLand,
          carbonCapacity = carbonCapacity,
          clickUpLink = clickUpLink,
          confirmedReforestableLand = confirmedReforestableLand,
          countryCode = countryCode,
          dealDescription = dealDescription,
          dealName = dealName,
          failureRisk = failureRisk,
          gisReportsLink = gisReportsLink,
          investmentThesis = investmentThesis,
          landUseModelTypes = landUseModelTypes,
          landUseModelHectares = landUseModelHectares,
          maxCarbonAccumulation = maxCarbonAccumulation,
          methodologyNumber = methodologyNumber,
          minCarbonAccumulation = minCarbonAccumulation,
          minProjectArea = minProjectArea,
          numNativeSpecies = numNativeSpecies,
          perHectareBudget = perHectareBudget,
          projectArea = projectArea,
          projectHighlightPhotoValueId = projectHighlightPhotoValueId,
          projectId = projectId,
          projectZoneFigureValueId = projectZoneFigureValueId,
          riskTrackerLink = riskTrackerLink,
          sdgList = sdgList,
          slackLink = slackLink,
          standard = standard,
          region = region,
          totalCarbon = totalCarbon,
          totalVCU = totalVCU,
          totalExpansionPotential = totalExpansionPotential,
          verraLink = verraLink,
          whatNeedsToBeTrue = whatNeedsToBeTrue,
      )
}
