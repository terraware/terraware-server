package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.RecordedTreeModel
import org.jooq.impl.DSL

internal val biomassSpeciesMultiset =
    with(OBSERVATION_BIOMASS_SPECIES) {
      DSL.multiset(
              DSL.select(
                      SPECIES_ID,
                      SCIENTIFIC_NAME,
                      COMMON_NAME,
                      IS_INVASIVE,
                      IS_THREATENED,
                  )
                  .from(this)
                  .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                  .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
          )
          .convertFrom { result ->
            result
                .map {
                  BiomassSpeciesModel(
                      commonName = it[COMMON_NAME],
                      scientificName = it[SCIENTIFIC_NAME],
                      speciesId = it[SPECIES_ID],
                      isInvasive = it[IS_INVASIVE]!!,
                      isThreatened = it[IS_THREATENED]!!,
                  )
                }
                .toSet()
          }
    }

internal val biomassQuadratDetailsMultiset =
    with(OBSERVATION_BIOMASS_QUADRAT_DETAILS) {
      DSL.multiset(
              DSL.select(
                      POSITION_ID,
                      DESCRIPTION,
                  )
                  .from(this)
                  .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                  .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
          )
          .convertFrom { result -> result.associate { it[POSITION_ID]!! to it[DESCRIPTION] } }
    }

internal val biomassQuadratSpeciesMultiset =
    with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
      DSL.multiset(
              DSL.select(
                      POSITION_ID,
                      ABUNDANCE_COUNT,
                      OBSERVATION_BIOMASS_SPECIES.SPECIES_ID,
                      OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME,
                  )
                  .from(this)
                  .join(OBSERVATION_BIOMASS_SPECIES)
                  .on(BIOMASS_SPECIES_ID.eq(OBSERVATION_BIOMASS_SPECIES.ID))
                  .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                  .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
          )
          .convertFrom { result ->
            result
                .groupBy { it[POSITION_ID]!! }
                .mapValues { (_, records) ->
                  records
                      .map {
                        BiomassQuadratSpeciesModel(
                            abundanceCount = it[ABUNDANCE_COUNT]!!,
                            speciesId = it[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                            speciesName = it[OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME],
                        )
                      }
                      .toSet()
                }
          }
    }

internal val recordedTreesGpsCoordinatesField = RECORDED_TREES.GPS_COORDINATES.forMultiset()

internal val recordedTreesMultiset =
    with(RECORDED_TREES) {
      DSL.multiset(
              DSL.select(
                      ID,
                      DESCRIPTION,
                      DIAMETER_AT_BREAST_HEIGHT_CM,
                      HEIGHT_M,
                      IS_DEAD,
                      POINT_OF_MEASUREMENT_M,
                      SHRUB_DIAMETER_CM,
                      recordedTreesBiomassSpeciesIdFkey.SPECIES_ID,
                      recordedTreesBiomassSpeciesIdFkey.SCIENTIFIC_NAME,
                      TREE_CROWN_DIAMETER_CM,
                      TREE_GROWTH_FORM_ID,
                      TREE_NUMBER,
                      TRUNK_NUMBER,
                      recordedTreesGpsCoordinatesField,
                  )
                  .from(this)
                  .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                  .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
                  .orderBy(TREE_NUMBER, TRUNK_NUMBER)
          )
          .convertFrom { result ->
            result.map { RecordedTreeModel.of(it, recordedTreesGpsCoordinatesField) }
          }
    }

internal val biomassDetailsMultiset =
    with(OBSERVATION_BIOMASS_DETAILS) {
      DSL.multiset(
              DSL.select(
                      biomassSpeciesMultiset,
                      biomassQuadratDetailsMultiset,
                      biomassQuadratSpeciesMultiset,
                      DESCRIPTION,
                      FOREST_TYPE_ID,
                      HERBACEOUS_COVER_PERCENT,
                      OBSERVATION_ID,
                      PH,
                      recordedTreesMultiset,
                      SALINITY_PPT,
                      SMALL_TREES_COUNT_LOW,
                      SMALL_TREES_COUNT_HIGH,
                      SOIL_ASSESSMENT,
                      MONITORING_PLOT_ID,
                      TIDE_ID,
                      TIDE_TIME,
                      WATER_DEPTH_CM,
                  )
                  .from(this)
                  .where(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .orderBy(MONITORING_PLOT_ID)
          )
          .convertFrom { result ->
            result.map { record ->
              val quadratDescriptions = record[biomassQuadratDetailsMultiset]
              val quadratSpecies = record[biomassQuadratSpeciesMultiset]
              val quadrats =
                  ObservationPlotPosition.entries.associateWith {
                    BiomassQuadratModel(
                        description = quadratDescriptions[it],
                        species = quadratSpecies[it] ?: emptySet(),
                    )
                  }

              ExistingBiomassDetailsModel(
                  description = record[DESCRIPTION],
                  forestType = record[FOREST_TYPE_ID]!!,
                  herbaceousCoverPercent = record[HERBACEOUS_COVER_PERCENT]!!,
                  observationId = record[OBSERVATION_ID]!!,
                  ph = record[PH],
                  quadrats = quadrats,
                  salinityPpt = record[SALINITY_PPT],
                  smallTreeCountRange =
                      record[SMALL_TREES_COUNT_LOW]!! to record[SMALL_TREES_COUNT_HIGH]!!,
                  soilAssessment = record[SOIL_ASSESSMENT]!!,
                  species = record[biomassSpeciesMultiset],
                  plotId = record[MONITORING_PLOT_ID]!!,
                  tide = record[TIDE_ID],
                  tideTime = record[TIDE_TIME],
                  trees = record[recordedTreesMultiset],
                  waterDepthCm = record[WATER_DEPTH_CM],
              )
            }
          }
    }
