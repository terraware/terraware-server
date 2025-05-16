package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.docprod.VariableValueId
import java.math.BigDecimal
import java.net.URI

/** Data class to hold project variable values. */
data class ProjectAcceleratorVariableValuesModel(
    val accumulationRate: BigDecimal? = null,
    val annualCarbon: BigDecimal? = null,
    val applicationReforestableLand: BigDecimal? = null,
    val carbonCapacity: BigDecimal? = null,
    val carbonCertifications: Set<CarbonCertification> = emptySet(),
    val clickUpLink: URI? = null,
    val confirmedReforestableLand: BigDecimal? = null,
    val countryAlpha3: String? = null,
    val countryCode: String? = null,
    val dealDescription: String? = null,
    val dealName: String? = null,
    val failureRisk: String? = null,
    val gisReportsLink: URI? = null,
    val investmentThesis: String? = null,
    val landUseModelTypes: Set<LandUseModelType> = emptySet(),
    val landUseModelHectares: Map<LandUseModelType, BigDecimal> = emptyMap(),
    val maxCarbonAccumulation: BigDecimal? = null,
    val methodologyNumber: String? = null,
    val minCarbonAccumulation: BigDecimal? = null,
    val minProjectArea: BigDecimal? = null,
    val numNativeSpecies: Int? = null,
    val perHectareBudget: BigDecimal? = null,
    val projectArea: BigDecimal? = null,
    val projectHighlightPhotoValueId: VariableValueId? = null,
    val projectId: ProjectId,
    val projectZoneFigureValueId: VariableValueId? = null,
    val riskTrackerLink: URI? = null,
    val sdgList: Set<SustainableDevelopmentGoal> = emptySet(),
    val slackLink: URI? = null,
    val standard: String? = null,
    val region: Region? = null,
    val totalCarbon: BigDecimal? = null,
    val totalExpansionPotential: BigDecimal? = null,
    val totalVCU: BigDecimal? = null,
    val verraLink: URI? = null,
    val whatNeedsToBeTrue: String? = null,
) {

  fun toProjectAcceleratorDetails() =
      ProjectAcceleratorDetailsModel(
          accumulationRate = accumulationRate,
          annualCarbon = annualCarbon,
          applicationReforestableLand = applicationReforestableLand,
          carbonCapacity = carbonCapacity,
          carbonCertifications = carbonCertifications,
          clickUpLink = clickUpLink,
          confirmedReforestableLand = confirmedReforestableLand,
          countryAlpha3 = countryAlpha3,
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
          totalExpansionPotential = totalExpansionPotential,
          totalVCU = totalVCU,
          verraLink = verraLink,
          whatNeedsToBeTrue = whatNeedsToBeTrue,
      )
}
