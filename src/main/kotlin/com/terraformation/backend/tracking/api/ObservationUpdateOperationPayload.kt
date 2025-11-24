package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.tracking.model.EditableBiomassDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassQuadratDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassSpeciesModel
import com.terraformation.backend.util.patchNullable
import io.swagger.v3.oas.annotations.media.Schema
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
