package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.LandUseModelType
import java.math.BigDecimal

enum class PreScreenProjectType {
  Mangrove,
  Terrestrial,
  Mixed
}

/**
 * Values of variables that are relevant to the pre-screen qualification check. This does not
 * include values that are part of [ExistingApplicationModel].
 */
data class PreScreenVariableValues(
    val landUseModelHectares: Map<LandUseModelType, BigDecimal>,
    val numSpeciesToBePlanted: Int?,
    val projectType: PreScreenProjectType?,
)
