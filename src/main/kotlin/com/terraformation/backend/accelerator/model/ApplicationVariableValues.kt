package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.LandUseModelType
import java.math.BigDecimal

enum class PreScreenProjectType {
  Mangrove,
  Terrestrial,
  Mixed,
}

/**
 * Values of variables that are relevant to the application submission logic, both pre-screening and
 * submitting for review. This does not include values that are part of [ExistingApplicationModel].
 */
data class ApplicationVariableValues(
    val contactEmail: String? = null,
    val contactName: String? = null,
    val countryCode: String?,
    val dealName: String? = null,
    val landUseModelHectares: Map<LandUseModelType, BigDecimal>,
    val numSpeciesToBePlanted: Int?,
    val projectType: PreScreenProjectType?,
    val totalExpansionPotential: BigDecimal?,
    val website: String? = null,
)
