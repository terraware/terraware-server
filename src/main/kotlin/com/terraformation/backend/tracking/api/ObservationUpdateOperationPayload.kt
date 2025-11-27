package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.tracking.model.EditableBiomassDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassQuadratDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassSpeciesModel
import com.terraformation.backend.tracking.model.EditableObservationPlotDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.util.patchNullable
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.util.Optional

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface ObservationUpdateOperationPayload

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
    val description: Optional<String?>?,
    val soilAssessment: String?,
) : ObservationUpdateOperationPayload {
  fun applyTo(model: EditableBiomassDetailsModel): EditableBiomassDetailsModel {
    return model.copy(
        description = description.patchNullable(model.description),
        soilAssessment = soilAssessment ?: model.soilAssessment,
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
