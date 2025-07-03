package com.terraformation.backend.funder.api

import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.funder.FunderProjectService
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import io.swagger.v3.oas.annotations.Operation
import java.math.BigDecimal
import java.net.URI
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@FunderEndpoint
@RequestMapping("/api/v1/funder/projects")
@RestController
class FundingProjectsController(
    private val funderProjectService: FunderProjectService,
) {

  @ApiResponse200
  @GetMapping("/{projectIds}")
  @Operation(summary = "Get published project details displayable to funders")
  fun getProjects(@PathVariable projectIds: Set<ProjectId>): GetFundingProjectResponsePayload {
    // Remove this section once FE is handling the list of projects returned
    if (projectIds.size == 1) {
      val model: FunderProjectDetailsModel? =
          funderProjectService.fetchByProjectId(projectIds.toList()[0])
      if (model == null) {
        throw ProjectNotFoundException(projectIds.toList()[0])
      }
      val payload = FunderProjectDetailsPayload(model)
      return GetFundingProjectResponsePayload(details = payload, projects = listOf(payload))
    }

    val models = funderProjectService.fetchListByProjectIds(projectIds)
    return GetFundingProjectResponsePayload(
        projects = models.map { FunderProjectDetailsPayload(it) })
  }

  @ApiResponse200
  @ApiResponse404
  @PostMapping("/{projectId}")
  @Operation(summary = "Publishes project detail information for funders")
  fun publishProjectProfile(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: PublishProjectProfileRequestPayload
  ): SimpleSuccessResponsePayload {
    funderProjectService.publishProjectProfile(payload.details.toModel(projectId))
    return SimpleSuccessResponsePayload()
  }
}

data class GetFundingProjectResponsePayload(
    val projects: List<FunderProjectDetailsPayload> = emptyList(),
    val details: FunderProjectDetailsPayload? =
        null, // Remove this property once FE is handling the list of projects returned
) : SuccessResponsePayload

data class FunderProjectDetailsPayload(
    val accumulationRate: BigDecimal?,
    val annualCarbon: BigDecimal?,
    val carbonCertifications: Set<CarbonCertification> = emptySet(),
    val confirmedReforestableLand: BigDecimal?,
    val countryAlpha3: String?,
    val countryCode: String?,
    val dealDescription: String?,
    val dealName: String?,
    val landUseModelTypes: Set<LandUseModelType>,
    val landUseModelHectares: Map<LandUseModelType, BigDecimal>,
    val methodologyNumber: String?,
    val minProjectArea: BigDecimal?,
    val numNativeSpecies: Int?,
    val perHectareBudget: BigDecimal?,
    val projectArea: BigDecimal?,
    val projectHighlightPhotoValueId: VariableValueId?,
    val projectId: ProjectId,
    val projectZoneFigureValueId: VariableValueId?,
    val sdgList: Set<SustainableDevelopmentGoal>,
    val standard: String?,
    val totalExpansionPotential: BigDecimal?,
    val totalVCU: BigDecimal?,
    val verraLink: URI?,
) {
  constructor(
      model: FunderProjectDetailsModel
  ) : this(
      accumulationRate = model.accumulationRate,
      annualCarbon = model.annualCarbon,
      carbonCertifications = model.carbonCertifications,
      confirmedReforestableLand = model.confirmedReforestableLand,
      countryAlpha3 = model.countryAlpha3,
      countryCode = model.countryCode,
      dealDescription = model.dealDescription,
      dealName = model.dealName,
      landUseModelTypes = model.landUseModelTypes,
      landUseModelHectares = model.landUseModelHectares,
      methodologyNumber = model.methodologyNumber,
      minProjectArea = model.minProjectArea,
      numNativeSpecies = model.numNativeSpecies,
      perHectareBudget = model.perHectareBudget,
      projectArea = model.projectArea,
      projectHighlightPhotoValueId = model.projectHighlightPhotoValueId,
      projectId = model.projectId,
      projectZoneFigureValueId = model.projectZoneFigureValueId,
      sdgList = model.sdgList,
      standard = model.standard,
      totalExpansionPotential = model.totalExpansionPotential,
      totalVCU = model.totalVCU,
      verraLink = model.verraLink,
  )

  fun toModel(projectId: ProjectId): FunderProjectDetailsModel {
    return FunderProjectDetailsModel(
        accumulationRate = this.accumulationRate,
        annualCarbon = this.annualCarbon,
        carbonCertifications = this.carbonCertifications,
        confirmedReforestableLand = this.confirmedReforestableLand,
        countryAlpha3 = this.countryAlpha3,
        countryCode = this.countryCode,
        dealDescription = this.dealDescription,
        dealName = this.dealName,
        landUseModelTypes = this.landUseModelTypes,
        landUseModelHectares = this.landUseModelHectares,
        methodologyNumber = this.methodologyNumber,
        minProjectArea = this.minProjectArea,
        numNativeSpecies = this.numNativeSpecies,
        perHectareBudget = this.perHectareBudget,
        projectArea = this.projectArea,
        projectHighlightPhotoValueId = this.projectHighlightPhotoValueId,
        projectId = projectId,
        projectZoneFigureValueId = this.projectZoneFigureValueId,
        sdgList = this.sdgList,
        standard = this.standard,
        totalExpansionPotential = this.totalExpansionPotential,
        totalVCU = this.totalVCU,
        verraLink = this.verraLink,
    )
  }
}

data class PublishProjectProfileRequestPayload(val details: FunderProjectDetailsPayload)
