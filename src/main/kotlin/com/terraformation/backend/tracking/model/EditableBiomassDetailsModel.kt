package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import org.jooq.Record

data class EditableBiomassDetailsModel(
    val description: String?,
    val soilAssessment: String,
) {
  companion object {
    fun of(record: Record): EditableBiomassDetailsModel {
      return with(OBSERVATION_BIOMASS_DETAILS) {
        EditableBiomassDetailsModel(
            description = record[DESCRIPTION],
            soilAssessment = record[SOIL_ASSESSMENT]!!,
        )
      }
    }
  }
}

data class EditableBiomassQuadratDetailsModel(
    val description: String? = null,
) {
  companion object {
    fun of(record: Record): EditableBiomassQuadratDetailsModel {
      return with(OBSERVATION_BIOMASS_QUADRAT_DETAILS) {
        EditableBiomassQuadratDetailsModel(
            description = record[DESCRIPTION],
        )
      }
    }
  }
}
