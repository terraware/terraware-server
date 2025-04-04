package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
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
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
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
        models.map { ProjectAcceleratorDetailsPayload(it) })
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
      @RequestBody payload: UpdateProjectAcceleratorDetailsRequestPayload
  ): SimpleSuccessResponsePayload {
    projectAcceleratorDetailsService.update(projectId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class ProjectAcceleratorDetailsPayload(
    val annualCarbon: BigDecimal?,
    val applicationReforestableLand: BigDecimal?,
    val carbonCapacity: BigDecimal?,
    val cohortId: CohortId?,
    val cohortName: String?,
    val cohortPhase: CohortPhase?,
    val confirmedReforestableLand: BigDecimal?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealName: String?,
    val dealStage: DealStage?,
    val dropboxFolderPath: String?,
    val failureRisk: String?,
    val fileNaming: String?,
    val googleFolderUrl: URI?,
    val hubSpotUrl: URI?,
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val maxCarbonAccumulation: BigDecimal?,
    val minCarbonAccumulation: BigDecimal?,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
    val participantId: ParticipantId?,
    val participantName: String?,
    val perHectareBudget: BigDecimal?,
    val pipeline: Pipeline?,
    val projectId: ProjectId,
    val projectLead: String?,
    val region: Region?,
    val totalCarbon: BigDecimal?,
    val totalExpansionPotential: BigDecimal?,
    val whatNeedsToBeTrue: String?,
) {
  constructor(
      model: ProjectAcceleratorDetailsModel
  ) : this(
      annualCarbon = model.annualCarbon,
      applicationReforestableLand = model.applicationReforestableLand,
      carbonCapacity = model.carbonCapacity,
      cohortId = model.cohortId,
      cohortName = model.cohortName,
      cohortPhase = model.cohortPhase,
      confirmedReforestableLand = model.confirmedReforestableLand,
      countryCode = model.countryCode,
      dealDescription = model.dealDescription,
      dealName = model.dealName,
      dealStage = model.dealStage,
      dropboxFolderPath = model.dropboxFolderPath,
      failureRisk = model.failureRisk,
      fileNaming = model.fileNaming,
      googleFolderUrl = model.googleFolderUrl,
      hubSpotUrl = model.hubSpotUrl,
      investmentThesis = model.investmentThesis,
      landUseModelTypes = model.landUseModelTypes,
      maxCarbonAccumulation = model.maxCarbonAccumulation,
      minCarbonAccumulation = model.minCarbonAccumulation,
      numCommunities = model.numCommunities,
      numNativeSpecies = model.numNativeSpecies,
      participantId = model.participantId,
      participantName = model.participantName,
      perHectareBudget = model.perHectareBudget,
      pipeline = model.pipeline,
      projectId = model.projectId,
      projectLead = model.projectLead,
      region = model.region,
      totalCarbon = model.totalCarbon,
      totalExpansionPotential = model.totalExpansionPotential,
      whatNeedsToBeTrue = model.whatNeedsToBeTrue,
  )
}

data class ListProjectAcceleratorDetailsResponsePayload(
    val details: List<ProjectAcceleratorDetailsPayload>,
) : SuccessResponsePayload

data class GetProjectAcceleratorDetailsResponsePayload(
    val details: ProjectAcceleratorDetailsPayload,
) : SuccessResponsePayload

data class UpdateProjectAcceleratorDetailsRequestPayload(
    val annualCarbon: BigDecimal?,
    val applicationReforestableLand: BigDecimal?,
    val carbonCapacity: BigDecimal?,
    val confirmedReforestableLand: BigDecimal?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealName: String?,
    val dealStage: DealStage?,
    @Schema(
        description =
            "Path on Dropbox to use for sensitive document storage. Ignored if the user does not " +
                "have permission to update project document settings.")
    val dropboxFolderPath: String?,
    val failureRisk: String?,
    val fileNaming: String?,
    @Schema(
        description =
            "URL of Google Drive folder to use for non-sensitive document storage. Ignored if " +
                "the user does not have permission to update project document settings.")
    val googleFolderUrl: URI?,
    val hubSpotUrl: URI?,
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val maxCarbonAccumulation: BigDecimal?,
    val minCarbonAccumulation: BigDecimal?,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
    val perHectareBudget: BigDecimal?,
    val pipeline: Pipeline?,
    val projectLead: String?,
    val totalCarbon: BigDecimal?,
    val totalExpansionPotential: BigDecimal?,
    val whatNeedsToBeTrue: String?,
) {
  fun applyTo(model: ProjectAcceleratorDetailsModel): ProjectAcceleratorDetailsModel =
      model.copy(
          annualCarbon = annualCarbon,
          applicationReforestableLand = applicationReforestableLand,
          carbonCapacity = carbonCapacity,
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
          investmentThesis = investmentThesis,
          landUseModelTypes = landUseModelTypes,
          maxCarbonAccumulation = maxCarbonAccumulation,
          minCarbonAccumulation = minCarbonAccumulation,
          numCommunities = numCommunities,
          numNativeSpecies = numNativeSpecies,
          perHectareBudget = perHectareBudget,
          pipeline = pipeline,
          projectLead = projectLead,
          totalCarbon = totalCarbon,
          totalExpansionPotential = totalExpansionPotential,
          whatNeedsToBeTrue = whatNeedsToBeTrue,
      )
}
