package com.terraformation.backend.funder.api

import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@FunderEndpoint
@RequestMapping("/api/v1/funder/projects")
@RestController
class FundingProjectsController(
    private val funderProjectService: FunderProjectService,
) {

  @ApiResponse200
  @GetMapping("/{projectId}")
  @Operation(summary = "Gets project detail information displayable to funders")
  fun getProject(@PathVariable projectId: ProjectId): GetFundingProjectResponsePayload {
    val model: FunderProjectDetailsModel = funderProjectService.fetchByProjectId(projectId)
    return GetFundingProjectResponsePayload(FunderProjectDetailsPayload(model))
  }
}

data class GetFundingProjectResponsePayload(
    val details: FunderProjectDetailsPayload,
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
}
