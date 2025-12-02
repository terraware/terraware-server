package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.tracking.model.EditableBiomassDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassQuadratDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.EditableBiomassSpeciesModel
import com.terraformation.backend.tracking.model.EditableObservationPlotDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.util.patchNullable
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface ObservationUpdateOperationPayload

@JsonTypeName("QuadratSpecies")
data class QuadratSpeciesUpdateOperationPayload(
    val abundance: Int?,
    val position: ObservationPlotPosition,
    @Schema(description = "ID of species to update. Either this or scientificName must be present.")
    val speciesId: SpeciesId?,
    @Schema(description = "Name of species to update. Either this or speciesId must be present.")
    val scientificName: String?,
) : ObservationUpdateOperationPayload {
  fun applyTo(model: EditableBiomassQuadratSpeciesModel): EditableBiomassQuadratSpeciesModel {
    return model.copy(
        abundance = abundance ?: model.abundance,
    )
  }
}

@JsonTypeName("Quadrat")
data class QuadratUpdateOperationPayload(
    val description: Optional<String>?,
    val position: ObservationPlotPosition,
) : ObservationUpdateOperationPayload {
  fun applyTo(model: EditableBiomassQuadratDetailsModel): EditableBiomassQuadratDetailsModel {
    return model.copy(
        description = description.patchNullable(model.description),
    )
  }
}

@JsonTypeName("BiomassSpecies")
data class BiomassSpeciesUpdateOperationPayload(
    val isInvasive: Boolean?,
    val isThreatened: Boolean?,
    @Schema(description = "ID of species to update. Either this or scientificName must be present.")
    val speciesId: SpeciesId?,
    @Schema(description = "Name of species to update. Either this or speciesId must be present.")
    val scientificName: String?,
) : ObservationUpdateOperationPayload {
  fun applyTo(model: EditableBiomassSpeciesModel): EditableBiomassSpeciesModel {
    return model.copy(
        isInvasive = isInvasive ?: model.isInvasive,
        isThreatened = isThreatened ?: model.isThreatened,
    )
  }
}

@JsonTypeName("Biomass")
data class BiomassUpdateOperationPayload(
    val description: Optional<String>?,
    val forestType: BiomassForestType?,
    val herbaceousCoverPercent: Int?,
    val ph: Optional<BigDecimal>?,
    val salinity: Optional<BigDecimal>?,
    val smallTreeCountLow: Int?,
    val smallTreeCountHigh: Int?,
    val soilAssessment: String?,
    val tide: Optional<MangroveTide>?,
    val tideTime: Optional<Instant>?,
    val waterDepth: Optional<Int>?,
) : ObservationUpdateOperationPayload {
  fun applyTo(model: EditableBiomassDetailsModel): EditableBiomassDetailsModel {
    return model.copy(
        description = description.patchNullable(model.description),
        forestType = forestType ?: model.forestType,
        herbaceousCoverPercent = herbaceousCoverPercent ?: model.herbaceousCoverPercent,
        ph = ph.patchNullable(model.ph),
        salinityPpt = salinity.patchNullable(model.salinityPpt),
        smallTreeCountRange =
            (smallTreeCountLow ?: model.smallTreeCountRange.first) to
                (smallTreeCountHigh ?: model.smallTreeCountRange.second),
        soilAssessment = soilAssessment ?: model.soilAssessment,
        tide = tide.patchNullable(model.tide),
        tideTime = tideTime.patchNullable(model.tideTime),
        waterDepthCm = waterDepth.patchNullable(model.waterDepthCm),
    )
  }
}

@JsonTypeName("ObservationPlot")
data class ObservationPlotUpdateOperationPayload(
    val conditions: Set<ObservableCondition>?,
    val notes: Optional<String>?,
) : ObservationUpdateOperationPayload {
  fun applyTo(model: EditableObservationPlotDetailsModel): EditableObservationPlotDetailsModel {
    return model.copy(
        conditions = conditions ?: model.conditions,
        notes = notes.patchNullable(model.notes),
    )
  }
}

@JsonTypeName("RecordedTree")
data class RecordedTreeUpdateOperationPayload(
    val description: Optional<String>?,
    @Schema(description = "Only valid for Tree and Trunk growth forms.") //
    val diameterAtBreastHeight: BigDecimal?,
    @Schema(description = "Only valid for Tree and Trunk growth forms.") //
    val height: BigDecimal?,
    val isDead: Boolean?,
    @Schema(description = "ID of tree to update.") //
    val recordedTreeId: RecordedTreeId,
    @Schema(description = "Only valid for Tree and Trunk growth forms.") //
    val pointOfMeasurement: BigDecimal?,
    @Schema(description = "Only valid for Shrub growth form.") //
    val shrubDiameter: Int?,
) : ObservationUpdateOperationPayload {
  fun applyTo(model: ExistingRecordedTreeModel): ExistingRecordedTreeModel {
    return model.copy(
        description = description.patchNullable(model.description),
        diameterAtBreastHeightCm = diameterAtBreastHeight ?: model.diameterAtBreastHeightCm,
        heightM = height ?: model.heightM,
        isDead = isDead ?: model.isDead,
        pointOfMeasurementM = pointOfMeasurement ?: model.pointOfMeasurementM,
        shrubDiameterCm = shrubDiameter ?: model.shrubDiameterCm,
    )
  }
}
