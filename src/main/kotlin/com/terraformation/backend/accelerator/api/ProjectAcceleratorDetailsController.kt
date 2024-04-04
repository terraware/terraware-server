package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.DealStage
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
@RequestMapping("/api/v1/accelerator/projects/{projectId}")
@RestController
class ProjectAcceleratorDetailsController(
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(
      summary = "Gets the accelerator-related details for a project.",
      description =
          "Does not include information such as project name that's available via the " +
              "non-accelerator projects API.")
  fun getProjectAcceleratorDetails(
      @PathVariable projectId: ProjectId
  ): GetProjectAcceleratorDetailsResponsePayload {
    val model = projectAcceleratorDetailsStore.fetchOneById(projectId)

    return GetProjectAcceleratorDetailsResponsePayload(ProjectAcceleratorDetailsPayload(model))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(summary = "Updates the accelerator-related details for a project.")
  @PutMapping
  fun updateProjectAcceleratorDetails(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateProjectAcceleratorDetailsRequestPayload
  ): SimpleSuccessResponsePayload {
    projectAcceleratorDetailsStore.update(projectId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class ProjectAcceleratorDetailsPayload(
    val applicationReforestableLand: BigDecimal?,
    val confirmedReforestableLand: BigDecimal?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealStage: DealStage?,
    val dropboxFolderPath: String?,
    val failureRisk: String?,
    val fileNaming: String?,
    val googleFolderUrl: URI?,
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val maxCarbonAccumulation: BigDecimal?,
    val minCarbonAccumulation: BigDecimal?,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
    val perHectareBudget: BigDecimal?,
    val pipeline: Pipeline?,
    val projectId: ProjectId,
    val projectLead: String?,
    val region: Region?,
    val totalExpansionPotential: BigDecimal?,
    val whatNeedsToBeTrue: String?,
) {
  constructor(
      model: ProjectAcceleratorDetailsModel
  ) : this(
      applicationReforestableLand = model.applicationReforestableLand,
      confirmedReforestableLand = model.confirmedReforestableLand,
      countryCode = model.countryCode,
      dealDescription = model.dealDescription,
      dealStage = model.dealStage,
      dropboxFolderPath = model.dropboxFolderPath,
      failureRisk = model.failureRisk,
      fileNaming = model.fileNaming,
      googleFolderUrl = model.googleFolderUrl,
      investmentThesis = model.investmentThesis,
      landUseModelTypes = model.landUseModelTypes,
      maxCarbonAccumulation = model.maxCarbonAccumulation,
      minCarbonAccumulation = model.minCarbonAccumulation,
      numCommunities = model.numCommunities,
      numNativeSpecies = model.numNativeSpecies,
      perHectareBudget = model.perHectareBudget,
      pipeline = model.pipeline,
      projectId = model.projectId,
      projectLead = model.projectLead,
      region = model.region,
      totalExpansionPotential = model.totalExpansionPotential,
      whatNeedsToBeTrue = model.whatNeedsToBeTrue,
  )
}

data class GetProjectAcceleratorDetailsResponsePayload(
    val details: ProjectAcceleratorDetailsPayload,
) : SuccessResponsePayload

data class UpdateProjectAcceleratorDetailsRequestPayload(
    val applicationReforestableLand: BigDecimal?,
    val confirmedReforestableLand: BigDecimal?,
    val countryCode: String?,
    val dealDescription: String?,
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
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val maxCarbonAccumulation: BigDecimal?,
    val minCarbonAccumulation: BigDecimal?,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
    val perHectareBudget: BigDecimal?,
    val pipeline: Pipeline?,
    val projectLead: String?,
    val totalExpansionPotential: BigDecimal?,
    val whatNeedsToBeTrue: String?,
) {
  fun applyTo(model: ProjectAcceleratorDetailsModel): ProjectAcceleratorDetailsModel =
      model.copy(
          applicationReforestableLand = applicationReforestableLand,
          confirmedReforestableLand = confirmedReforestableLand,
          countryCode = countryCode,
          dealDescription = dealDescription,
          dealStage = dealStage,
          dropboxFolderPath = dropboxFolderPath,
          failureRisk = failureRisk,
          fileNaming = fileNaming,
          googleFolderUrl = googleFolderUrl,
          investmentThesis = investmentThesis,
          landUseModelTypes = landUseModelTypes,
          maxCarbonAccumulation = maxCarbonAccumulation,
          minCarbonAccumulation = minCarbonAccumulation,
          numCommunities = numCommunities,
          numNativeSpecies = numNativeSpecies,
          perHectareBudget = perHectareBudget,
          pipeline = pipeline,
          projectLead = projectLead,
          totalExpansionPotential = totalExpansionPotential,
          whatNeedsToBeTrue = whatNeedsToBeTrue,
      )
}
