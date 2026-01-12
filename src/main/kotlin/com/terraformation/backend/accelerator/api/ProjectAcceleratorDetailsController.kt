package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.MetricProgressModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.docprod.VariableValueId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.net.URI
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/projects")
@RestController
class ProjectAcceleratorDetailsController(
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
) {
  @ApiResponse200
  @ApiResponse400
  @GetMapping
  @Operation(
      summary = "List accelerator projects with accelerator-related details.",
  )
  fun listProjectAcceleratorDetails(): ListProjectAcceleratorDetailsResponsePayload {
    val models = projectAcceleratorDetailsService.fetchAllParticipantProjectDetails()
    return ListProjectAcceleratorDetailsResponsePayload(
        models.map { ProjectAcceleratorDetailsPayload(it) }
    )
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{projectId}")
  @Operation(
      summary = "Gets the accelerator-related details for a project.",
  )
  fun getProjectAcceleratorDetails(
      @PathVariable projectId: ProjectId
  ): GetProjectAcceleratorDetailsResponsePayload {
    val model = projectAcceleratorDetailsService.fetchOneById(projectId)

    return GetProjectAcceleratorDetailsResponsePayload(ProjectAcceleratorDetailsPayload(model))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(summary = "Updates the accelerator-related details for a project.")
  @PutMapping("/{projectId}")
  fun updateProjectAcceleratorDetails(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateProjectAcceleratorDetailsRequestPayload,
  ): SimpleSuccessResponsePayload {
    projectAcceleratorDetailsService.update(projectId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class ProjectAcceleratorDetailsPayload(
    val accumulationRate: BigDecimal?,
    val annualCarbon: BigDecimal?,
    val applicationReforestableLand: BigDecimal?,
    val carbonCapacity: BigDecimal?,
    val carbonCertifications: Set<CarbonCertification>?,
    val clickUpLink: URI?,
    val cohortId: CohortId?,
    val cohortName: String?,
    val cohortPhase: CohortPhase?,
    val confirmedReforestableLand: BigDecimal?,
    val countryAlpha3: String?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealName: String?,
    val dealStage: DealStage?,
    val dropboxFolderPath: String?,
    val failureRisk: String?,
    val gisReportsLink: URI?,
    val fileNaming: String?,
    val googleFolderUrl: URI?,
    val hubSpotUrl: URI?,
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val landUseModelHectares: Map<LandUseModelType, BigDecimal>?,
    val maxCarbonAccumulation: BigDecimal?,
    val methodologyNumber: String?,
    val metricProgress: List<MetricProgressPayload>,
    val minCarbonAccumulation: BigDecimal?,
    val minProjectArea: BigDecimal?,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
    val perHectareBudget: BigDecimal?,
    val pipeline: Pipeline?,
    val plantingSitesCql: String?,
    val projectArea: BigDecimal?,
    val projectBoundariesCql: String?,
    val projectHighlightPhotoValueId: VariableValueId?,
    val projectId: ProjectId,
    val projectZoneFigureValueId: VariableValueId?,
    val region: Region?,
    val riskTrackerLink: URI?,
    val sdgList: Set<SustainableDevelopmentGoal>?,
    val slackLink: URI?,
    val standard: String?,
    val totalCarbon: BigDecimal?,
    val totalExpansionPotential: BigDecimal?,
    val totalVCU: BigDecimal?,
    val verraLink: URI?,
    val whatNeedsToBeTrue: String?,
) {
  constructor(
      model: ProjectAcceleratorDetailsModel
  ) : this(
      accumulationRate = model.accumulationRate,
      annualCarbon = model.annualCarbon,
      applicationReforestableLand = model.applicationReforestableLand,
      carbonCapacity = model.carbonCapacity,
      carbonCertifications = model.carbonCertifications,
      clickUpLink = model.clickUpLink,
      cohortId = model.cohortId,
      cohortName = model.cohortName,
      cohortPhase = model.cohortPhase,
      confirmedReforestableLand = model.confirmedReforestableLand,
      countryAlpha3 = model.countryAlpha3,
      countryCode = model.countryCode,
      dealDescription = model.dealDescription,
      dealName = model.dealName,
      dealStage = model.dealStage,
      dropboxFolderPath = model.dropboxFolderPath,
      failureRisk = model.failureRisk,
      gisReportsLink = model.gisReportsLink,
      fileNaming = model.fileNaming,
      googleFolderUrl = model.googleFolderUrl,
      hubSpotUrl = model.hubSpotUrl,
      investmentThesis = model.investmentThesis,
      landUseModelTypes = model.landUseModelTypes,
      landUseModelHectares = model.landUseModelHectares,
      maxCarbonAccumulation = model.maxCarbonAccumulation,
      methodologyNumber = model.methodologyNumber,
      metricProgress = model.metricProgress.map { MetricProgressPayload(it) },
      minCarbonAccumulation = model.minCarbonAccumulation,
      minProjectArea = model.minProjectArea,
      numCommunities = model.numCommunities,
      numNativeSpecies = model.numNativeSpecies,
      perHectareBudget = model.perHectareBudget,
      pipeline = model.pipeline,
      plantingSitesCql = model.plantingSitesCql,
      projectArea = model.projectArea,
      projectBoundariesCql = model.projectBoundariesCql,
      projectHighlightPhotoValueId = model.projectHighlightPhotoValueId,
      projectId = model.projectId,
      projectZoneFigureValueId = model.projectZoneFigureValueId,
      region = model.region,
      riskTrackerLink = model.riskTrackerLink,
      sdgList = model.sdgList,
      slackLink = model.slackLink,
      standard = model.standard,
      totalCarbon = model.totalCarbon,
      totalExpansionPotential = model.totalExpansionPotential,
      totalVCU = model.totalVCU,
      verraLink = model.verraLink,
      whatNeedsToBeTrue = model.whatNeedsToBeTrue,
  )
}

data class MetricProgressPayload(
    val metric: SystemMetric,
    val progress: Int,
) {
  constructor(
      model: MetricProgressModel
  ) : this(
      metric = model.metric,
      progress = model.progress,
  )
}

data class ListProjectAcceleratorDetailsResponsePayload(
    val details: List<ProjectAcceleratorDetailsPayload>,
) : SuccessResponsePayload

data class GetProjectAcceleratorDetailsResponsePayload(
    val details: ProjectAcceleratorDetailsPayload,
) : SuccessResponsePayload

data class UpdateProjectAcceleratorDetailsRequestPayload(
    val accumulationRate: BigDecimal? = null,
    val annualCarbon: BigDecimal?,
    val applicationReforestableLand: BigDecimal?,
    val carbonCapacity: BigDecimal?,
    val carbonCertifications: Set<CarbonCertification>? = emptySet(),
    val clickUpLink: URI? = null,
    val confirmedReforestableLand: BigDecimal?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealName: String?,
    val dealStage: DealStage?,
    @Schema(
        description =
            "Path on Dropbox to use for sensitive document storage. Ignored if the user does not " +
                "have permission to update project document settings."
    )
    val dropboxFolderPath: String?,
    val failureRisk: String?,
    val gisReportsLink: URI? = null,
    val fileNaming: String?,
    @Schema(
        description =
            "URL of Google Drive folder to use for non-sensitive document storage. Ignored if " +
                "the user does not have permission to update project document settings."
    )
    val googleFolderUrl: URI?,
    val hubSpotUrl: URI?,
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val landUseModelHectares: Map<LandUseModelType, BigDecimal>? = emptyMap(),
    val maxCarbonAccumulation: BigDecimal?,
    val methodologyNumber: String? = null,
    val minCarbonAccumulation: BigDecimal?,
    val minProjectArea: BigDecimal? = null,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
    val perHectareBudget: BigDecimal?,
    val pipeline: Pipeline?,
    val projectArea: BigDecimal? = null,
    val riskTrackerLink: URI? = null,
    val sdgList: Set<SustainableDevelopmentGoal>? = emptySet(),
    val slackLink: URI? = null,
    val standard: String? = null,
    val totalCarbon: BigDecimal?,
    val totalExpansionPotential: BigDecimal?,
    val totalVCU: BigDecimal? = null,
    val verraLink: URI? = null,
    val whatNeedsToBeTrue: String?,
) {
  fun applyTo(model: ProjectAcceleratorDetailsModel): ProjectAcceleratorDetailsModel =
      model.copy(
          accumulationRate = accumulationRate,
          annualCarbon = annualCarbon,
          applicationReforestableLand = applicationReforestableLand,
          carbonCapacity = carbonCapacity,
          carbonCertifications = carbonCertifications ?: emptySet(),
          clickUpLink = clickUpLink,
          confirmedReforestableLand = confirmedReforestableLand,
          countryCode = countryCode,
          dealDescription = dealDescription,
          dealName = dealName ?: model.dealName,
          dealStage = dealStage,
          dropboxFolderPath = dropboxFolderPath,
          failureRisk = failureRisk,
          fileNaming = fileNaming,
          googleFolderUrl = googleFolderUrl,
          hubSpotUrl = hubSpotUrl,
          gisReportsLink = gisReportsLink,
          investmentThesis = investmentThesis,
          landUseModelTypes = landUseModelTypes,
          landUseModelHectares = landUseModelHectares.orEmpty(),
          maxCarbonAccumulation = maxCarbonAccumulation,
          methodologyNumber = methodologyNumber,
          minCarbonAccumulation = minCarbonAccumulation,
          minProjectArea = minProjectArea,
          numCommunities = numCommunities,
          numNativeSpecies = numNativeSpecies,
          perHectareBudget = perHectareBudget,
          pipeline = pipeline,
          projectArea = projectArea,
          riskTrackerLink = riskTrackerLink,
          sdgList = sdgList.orEmpty(),
          slackLink = slackLink,
          standard = standard,
          totalCarbon = totalCarbon,
          totalVCU = totalVCU,
          totalExpansionPotential = totalExpansionPotential,
          verraLink = verraLink,
          whatNeedsToBeTrue = whatNeedsToBeTrue,
      )
}
