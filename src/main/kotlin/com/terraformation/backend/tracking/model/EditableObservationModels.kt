package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.tracking.event.ObservationPlotEditedEventValues
import com.terraformation.backend.util.nullIfEquals
import org.jooq.Field
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

data class EditableBiomassSpeciesModel(
    val isInvasive: Boolean,
    val isThreatened: Boolean,
) {
  companion object {
    fun of(record: Record): EditableBiomassSpeciesModel {
      return with(OBSERVATION_BIOMASS_SPECIES) {
        EditableBiomassSpeciesModel(
            isInvasive = record[IS_INVASIVE]!!,
            isThreatened = record[IS_THREATENED]!!,
        )
      }
    }
  }
}

data class EditableObservationPlotDetailsModel(
    val conditions: Set<ObservableCondition>,
    val notes: String?,
) {
  fun toEventValues(other: EditableObservationPlotDetailsModel) =
      ObservationPlotEditedEventValues(
          conditions = conditions.nullIfEquals(other.conditions),
          notes = notes.nullIfEquals(other.notes),
      )

  companion object {
    fun of(
        record: Record,
        conditionsField: Field<Set<ObservableCondition>?>,
    ): EditableObservationPlotDetailsModel {
      return with(OBSERVATION_PLOTS) {
        EditableObservationPlotDetailsModel(
            conditions = record[conditionsField] ?: emptySet(),
            notes = record[NOTES],
        )
      }
    }
  }
}
