package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEventValues
import com.terraformation.backend.tracking.event.BiomassQuadratDetailsUpdatedEventValues
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEventValues
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEventValues
import com.terraformation.backend.tracking.event.MonitoringSpeciesTotalsEditedEventValues
import com.terraformation.backend.tracking.event.ObservationPlotEditedEventValues
import com.terraformation.backend.util.nullIfEquals
import java.math.BigDecimal
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class EditableBiomassDetailsModel(
    val description: String?,
    val forestType: BiomassForestType,
    val herbaceousCoverPercent: Int,
    val ph: BigDecimal?,
    val salinityPpt: BigDecimal?,
    val smallTreeCountRange: Pair<Int, Int>,
    val soilAssessment: String,
    val tide: MangroveTide?,
    val tideTime: Instant?,
    val waterDepthCm: Int?,
) {
  fun toEventValues(other: EditableBiomassDetailsModel) =
      BiomassDetailsUpdatedEventValues(
          description = description.nullIfEquals(other.description),
          forestType = forestType.nullIfEquals(other.forestType),
          herbaceousCoverPercent =
              herbaceousCoverPercent.nullIfEquals(other.herbaceousCoverPercent),
          ph = ph.nullIfEquals(other.ph),
          salinity = salinityPpt.nullIfEquals(other.salinityPpt),
          smallTreeCountRange = smallTreeCountRange.nullIfEquals(other.smallTreeCountRange),
          soilAssessment = soilAssessment.nullIfEquals(other.soilAssessment),
          tide = tide.nullIfEquals(other.tide),
          tideTime = tideTime.nullIfEquals(other.tideTime),
          waterDepth = waterDepthCm.nullIfEquals(other.waterDepthCm),
      )

  /** Forces mangrove-only properties to null if this is a terrestrial observation. */
  fun sanitizeForForestType(): EditableBiomassDetailsModel =
      when (forestType) {
        BiomassForestType.Mangrove -> this
        BiomassForestType.Terrestrial ->
            copy(
                ph = null,
                salinityPpt = null,
                tide = null,
                tideTime = null,
                waterDepthCm = null,
            )
      }

  companion object {
    fun of(record: Record): EditableBiomassDetailsModel {
      return with(OBSERVATION_BIOMASS_DETAILS) {
        EditableBiomassDetailsModel(
            description = record[DESCRIPTION],
            forestType = record[FOREST_TYPE_ID]!!,
            herbaceousCoverPercent = record[HERBACEOUS_COVER_PERCENT]!!,
            ph = record[PH],
            salinityPpt = record[SALINITY_PPT],
            smallTreeCountRange =
                record[SMALL_TREES_COUNT_LOW]!! to record[SMALL_TREES_COUNT_HIGH]!!,
            soilAssessment = record[SOIL_ASSESSMENT]!!,
            tide = record[TIDE_ID],
            tideTime = record[TIDE_TIME],
            waterDepthCm = record[WATER_DEPTH_CM],
        )
      }
    }
  }
}

data class EditableBiomassQuadratDetailsModel(
    val description: String? = null,
) {
  fun toEventValues(other: EditableBiomassQuadratDetailsModel) =
      BiomassQuadratDetailsUpdatedEventValues(
          description = description.nullIfEquals(other.description),
      )

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

data class EditableBiomassQuadratSpeciesModel(
    val abundance: Int,
) {
  fun toEventValues(other: EditableBiomassQuadratSpeciesModel) =
      BiomassQuadratSpeciesUpdatedEventValues(
          abundance = abundance.nullIfEquals(other.abundance),
      )

  companion object {
    fun of(record: Record): EditableBiomassQuadratSpeciesModel {
      return with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
        EditableBiomassQuadratSpeciesModel(
            abundance = record[ABUNDANCE_PERCENT]!!,
        )
      }
    }
  }
}

data class EditableBiomassSpeciesModel(
    val isInvasive: Boolean,
    val isThreatened: Boolean,
) {
  fun toEventValues(other: EditableBiomassSpeciesModel) =
      BiomassSpeciesUpdatedEventValues(
          isInvasive = isInvasive.nullIfEquals(other.isInvasive),
          isThreatened = isThreatened.nullIfEquals(other.isThreatened),
      )

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

data class EditableMonitoringSpeciesModel(
    val totalDead: Int,
    val totalExisting: Int,
    val totalLive: Int,
) {
  fun toEventValues(other: EditableMonitoringSpeciesModel) =
      MonitoringSpeciesTotalsEditedEventValues(
          totalDead = totalDead.nullIfEquals(other.totalDead),
          totalExisting = totalExisting.nullIfEquals(other.totalExisting),
          totalLive = totalLive.nullIfEquals(other.totalLive),
      )

  companion object {
    fun of(record: Record): EditableMonitoringSpeciesModel {
      return with(OBSERVED_PLOT_SPECIES_TOTALS) {
        EditableMonitoringSpeciesModel(
            totalDead = record[TOTAL_DEAD]!!,
            totalExisting = record[TOTAL_EXISTING]!!,
            totalLive = record[TOTAL_LIVE]!!,
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
