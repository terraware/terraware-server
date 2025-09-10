package com.terraformation.backend.funder.model

import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.MetricProgressModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.funder.tables.records.PublishedProjectDetailsRecord
import java.math.BigDecimal
import java.net.URI

data class FunderProjectDetailsModel(
    val accumulationRate: BigDecimal? = null,
    val annualCarbon: BigDecimal? = null,
    val carbonCertifications: Set<CarbonCertification> = emptySet(),
    val confirmedReforestableLand: BigDecimal? = null,
    val countryAlpha3: String? = null,
    val countryCode: String? = null,
    val dealDescription: String? = null,
    val dealName: String? = null,
    val landUseModelTypes: Set<LandUseModelType> = emptySet(),
    val landUseModelHectares: Map<LandUseModelType, BigDecimal> = emptyMap(),
    val metricProgress: List<MetricProgressModel> = emptyList(),
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
          carbonCertifications = details.carbonCertifications,
          confirmedReforestableLand = details.confirmedReforestableLand,
          countryAlpha3 = details.countryAlpha3,
          countryCode = details.countryCode,
          dealDescription = details.dealDescription,
          dealName = details.dealName,
          landUseModelTypes = details.landUseModelTypes,
          landUseModelHectares = details.landUseModelHectares,
          methodologyNumber = details.methodologyNumber,
          metricProgress = details.metricProgress,
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

    fun of(
        record: PublishedProjectDetailsRecord,
        carbonCertifications: Set<CarbonCertification> = emptySet(),
        sdgList: Set<SustainableDevelopmentGoal> = emptySet(),
        landUsages: Map<LandUseModelType, BigDecimal?> = emptyMap(),
        metricProgress: List<MetricProgressModel> = emptyList(),
    ): FunderProjectDetailsModel {
      return FunderProjectDetailsModel(
          accumulationRate = record.accumulationRate,
          annualCarbon = record.annualCarbon,
          carbonCertifications = carbonCertifications,
          confirmedReforestableLand = record.tfReforestableLand,
          countryCode = record.countryCode,
          dealDescription = record.dealDescription,
          dealName = record.dealName,
          landUseModelTypes = landUsages.keys.toSet(),
          landUseModelHectares = landUsages.filterValues { it != null }.mapValues { it.value!! },
          methodologyNumber = record.methodologyNumber,
          metricProgress = metricProgress,
          minProjectArea = record.minProjectArea,
          numNativeSpecies = record.numNativeSpecies,
          perHectareBudget = record.perHectareEstimatedBudget,
          projectArea = record.projectArea,
          projectHighlightPhotoValueId =
              record.projectHighlightPhotoValueId?.let { VariableValueId(it) },
          projectId = record.projectId!!,
          projectZoneFigureValueId = record.projectZoneFigureValueId?.let { VariableValueId(it) },
          sdgList = sdgList,
          standard = record.standard,
          totalExpansionPotential = record.totalExpansionPotential,
          totalVCU = record.totalVcu,
          verraLink = record.verraLink?.let { URI(it) },
      )
    }
  }
}
