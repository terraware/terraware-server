package com.terraformation.backend.funder.model

import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableValueId
import java.math.BigDecimal
import java.net.URI

data class FunderProjectDetailsModel(
    val accumulationRate: BigDecimal? = null,
    val annualCarbon: BigDecimal? = null,
    val confirmedReforestableLand: BigDecimal? = null,
    val countryAlpha3: String? = null,
    val countryCode: String? = null,
    val dealDescription: String? = null,
    val dealName: String? = null,
    val landUseModelTypes: Set<LandUseModelType> = emptySet(),
    val landUseModelHectares: Map<LandUseModelType, BigDecimal> = emptyMap(),
    val methodologyNumber: String? = null,
    val minProjectArea: BigDecimal? = null,
    val numNativeSpecies: Int? = null,
    val perHectareBudget: BigDecimal? = null,
    val projectArea: BigDecimal? = null,
    val projectHighlightPhotoValueId: VariableValueId? = null,
    val projectId: ProjectId,
    val projectZoneFigureValueId: VariableValueId? = null,
    val sdgList: Set<SustainableDevelopmentGoal> = emptySet(),
    val standard: String? = null,
    val totalExpansionPotential: BigDecimal? = null,
    val totalVCU: BigDecimal? = null,
    val verraLink: URI? = null,
) {
  companion object {
    fun of(details: ProjectAcceleratorDetailsModel): FunderProjectDetailsModel {
      return FunderProjectDetailsModel(
          accumulationRate = details.accumulationRate,
          annualCarbon = details.annualCarbon,
          confirmedReforestableLand = details.confirmedReforestableLand,
          countryAlpha3 = details.countryAlpha3,
          countryCode = details.countryCode,
          dealDescription = details.dealDescription,
          dealName = details.dealName,
          landUseModelTypes = details.landUseModelTypes,
          landUseModelHectares = details.landUseModelHectares,
          methodologyNumber = details.methodologyNumber,
          minProjectArea = details.minProjectArea,
          numNativeSpecies = details.numNativeSpecies,
          perHectareBudget = details.perHectareBudget,
          projectArea = details.projectArea,
          projectHighlightPhotoValueId = details.projectHighlightPhotoValueId,
          projectId = details.projectId,
          projectZoneFigureValueId = details.projectZoneFigureValueId,
          sdgList = details.sdgList,
          standard = details.standard,
          totalExpansionPotential = details.totalExpansionPotential,
          totalVCU = details.totalVCU,
          verraLink = details.verraLink,
      )
    }
  }
}
