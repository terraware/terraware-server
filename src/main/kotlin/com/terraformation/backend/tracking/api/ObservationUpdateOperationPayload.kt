package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.tracking.model.EditableBiomassDetailsModel
import com.terraformation.backend.util.patchNullable
import java.util.Optional

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface ObservationUpdateOperationPayload

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
