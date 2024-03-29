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
import java.math.BigDecimal
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
    val abbreviatedName: String?,
    val applicationReforestableLand: BigDecimal?,
    val confirmedReforestableLand: BigDecimal?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealStage: DealStage?,
    val failureRisk: String?,
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val maxCarbonAccumulation: BigDecimal?,
    val minCarbonAccumulation: BigDecimal?,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
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
      abbreviatedName = model.abbreviatedName,
      applicationReforestableLand = model.applicationReforestableLand,
      confirmedReforestableLand = model.confirmedReforestableLand,
      countryCode = model.countryCode,
      dealDescription = model.dealDescription,
      dealStage = model.dealStage,
      failureRisk = model.failureRisk,
      investmentThesis = model.investmentThesis,
      landUseModelTypes = model.landUseModelTypes,
      maxCarbonAccumulation = model.maxCarbonAccumulation,
      minCarbonAccumulation = model.minCarbonAccumulation,
      numCommunities = model.numCommunities,
      numNativeSpecies = model.numNativeSpecies,
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
    val abbreviatedName: String?,
    val applicationReforestableLand: BigDecimal?,
    val confirmedReforestableLand: BigDecimal?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealStage: DealStage?,
    val failureRisk: String?,
    val investmentThesis: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val maxCarbonAccumulation: BigDecimal?,
    val minCarbonAccumulation: BigDecimal?,
    val numCommunities: Int?,
    val numNativeSpecies: Int?,
    val pipeline: Pipeline?,
    val projectLead: String?,
    val totalExpansionPotential: BigDecimal?,
    val whatNeedsToBeTrue: String?,
) {
  fun applyTo(model: ProjectAcceleratorDetailsModel): ProjectAcceleratorDetailsModel =
      model.copy(
          abbreviatedName = abbreviatedName,
          applicationReforestableLand = applicationReforestableLand,
          confirmedReforestableLand = confirmedReforestableLand,
          countryCode = countryCode,
          dealDescription = dealDescription,
          dealStage = dealStage,
          failureRisk = failureRisk,
          investmentThesis = investmentThesis,
          landUseModelTypes = landUseModelTypes,
          maxCarbonAccumulation = maxCarbonAccumulation,
          minCarbonAccumulation = minCarbonAccumulation,
          numCommunities = numCommunities,
          numNativeSpecies = numNativeSpecies,
          pipeline = pipeline,
          projectLead = projectLead,
          totalExpansionPotential = totalExpansionPotential,
          whatNeedsToBeTrue = whatNeedsToBeTrue,
      )
}
