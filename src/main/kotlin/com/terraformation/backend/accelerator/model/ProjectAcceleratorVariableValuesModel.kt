package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import java.math.BigDecimal

/** Data class to hold project variable values. */
data class ProjectAcceleratorVariableValuesModel(
    val annualCarbon: BigDecimal? = null,
    val applicationReforestableLand: BigDecimal? = null,
    val carbonCapacity: BigDecimal? = null,
    val confirmedReforestableLand: BigDecimal? = null,
    val countryCode: String? = null,
    val dealDescription: String? = null,
    val failureRisk: String? = null,
    val investmentThesis: String? = null,
    val landUseModelTypes: Set<LandUseModelType> = emptySet(),
    val maxCarbonAccumulation: BigDecimal? = null,
    val minCarbonAccumulation: BigDecimal? = null,
    val numNativeSpecies: Int? = null,
    val perHectareBudget: BigDecimal? = null,
    val projectId: ProjectId,
    val region: Region? = null,
    val totalCarbon: BigDecimal? = null,
    val totalExpansionPotential: BigDecimal? = null,
    val whatNeedsToBeTrue: String? = null,
) {

  fun toProjectAcceleratorDetails() =
      ProjectAcceleratorDetailsModel(
          annualCarbon = annualCarbon,
          applicationReforestableLand = applicationReforestableLand,
          carbonCapacity = carbonCapacity,
          confirmedReforestableLand = confirmedReforestableLand,
          countryCode = countryCode,
          dealDescription = dealDescription,
          failureRisk = failureRisk,
          investmentThesis = investmentThesis,
          landUseModelTypes = landUseModelTypes,
          maxCarbonAccumulation = maxCarbonAccumulation,
          minCarbonAccumulation = minCarbonAccumulation,
          numNativeSpecies = numNativeSpecies,
          perHectareBudget = perHectareBudget,
          projectId = projectId,
          region = region,
          totalCarbon = totalCarbon,
          totalExpansionPotential = totalExpansionPotential,
          whatNeedsToBeTrue = whatNeedsToBeTrue)
}
