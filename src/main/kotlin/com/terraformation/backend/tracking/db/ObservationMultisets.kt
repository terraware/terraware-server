package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesIdConverter
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.PlantingSiteIdConverter
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumIdConverter
import com.terraformation.backend.db.tracking.SubstratumIdConverter
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_OVERLAPS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotMediaModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.RecordedPlantModel
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record10
import org.jooq.Select
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.locationtech.jts.geom.Point

internal val observationPlotCoordinatesGpsField =
    OBSERVED_PLOT_COORDINATES.GPS_COORDINATES.forMultiset()

internal val observationPlotCoordinatesMultiset =
    DSL.multiset(
            DSL.select(
                    OBSERVED_PLOT_COORDINATES.ID,
                    observationPlotCoordinatesGpsField,
                    OBSERVED_PLOT_COORDINATES.POSITION_ID,
                )
                .from(OBSERVED_PLOT_COORDINATES)
                .where(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .and(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                .orderBy(OBSERVED_PLOT_COORDINATES.POSITION_ID)
        )
        .convertFrom { result ->
          result.map { record ->
            ObservedPlotCoordinatesModel(
                id = record[OBSERVED_PLOT_COORDINATES.ID.asNonNullable()],
                gpsCoordinates =
                    record[observationPlotCoordinatesGpsField.asNonNullable()].centroid,
                position = record[OBSERVED_PLOT_COORDINATES.POSITION_ID.asNonNullable()],
            )
          }
        }

internal val filesGeolocationField = FILES.GEOLOCATION.forMultiset()

internal val observationMediaMultiset =
    DSL.multiset(
            DSL.select(
                    FILES.CONTENT_TYPE,
                    filesGeolocationField,
                    OBSERVATION_MEDIA_FILES.CAPTION,
                    OBSERVATION_MEDIA_FILES.FILE_ID,
                    OBSERVATION_MEDIA_FILES.IS_ORIGINAL,
                    OBSERVATION_MEDIA_FILES.POSITION_ID,
                    OBSERVATION_MEDIA_FILES.TYPE_ID,
                )
                .from(OBSERVATION_MEDIA_FILES)
                .join(FILES)
                .on(OBSERVATION_MEDIA_FILES.FILE_ID.eq(FILES.ID))
                .where(OBSERVATION_MEDIA_FILES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .and(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                .orderBy(OBSERVATION_MEDIA_FILES.FILE_ID)
        )
        .convertFrom { result ->
          result.map { record ->
            ObservationMonitoringPlotMediaModel(
                caption = record[OBSERVATION_MEDIA_FILES.CAPTION],
                contentType = record[FILES.CONTENT_TYPE.asNonNullable()],
                fileId = record[OBSERVATION_MEDIA_FILES.FILE_ID.asNonNullable()],
                gpsCoordinates = record[filesGeolocationField]?.centroid,
                isOriginal = record[OBSERVATION_MEDIA_FILES.IS_ORIGINAL.asNonNullable()],
                position = record[OBSERVATION_MEDIA_FILES.POSITION_ID],
                type = record[OBSERVATION_MEDIA_FILES.TYPE_ID.asNonNullable()],
            )
          }
        }

internal fun plotHasCompletedObservations(
    monitoringPlotIdField: Field<MonitoringPlotId?>,
    isPermanent: Boolean,
): Condition =
    DSL.exists(
        DSL.selectOne()
            .from(
                DSL.select(OBSERVATION_PLOTS.IS_PERMANENT)
                    .from(OBSERVATION_PLOTS)
                    .where(
                        OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotIdField)
                            .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull)
                    )
                    .and(OBSERVATION_PLOTS.COMPLETED_TIME.le(OBSERVATIONS.COMPLETED_TIME))
                    .orderBy(
                        OBSERVATION_PLOTS.COMPLETED_TIME.desc(),
                        OBSERVATION_PLOTS.OBSERVATION_ID.desc(),
                    )
                    .limit(1)
                    .asTable("most_recent")
            )
            .where(DSL.field("most_recent.IS_PERMANENT", Boolean::class.java).eq(isPermanent))
    )

/**
 * Returns a field that converts query results with per-species totals to
 * [ObservationSpeciesResultsModel] objects.
 *
 * @param query A query with the following fields in the following order:
 *     1. Certainty (non-nullable)
 *     2. Species ID (nullable)
 *     3. Species name (nullable)
 *     4. Total live (non-nullable)
 *     5. Total dead (non-nullable)
 *     6. Total existing (non-nullable)
 *     7. Total live in permanent plots (non-nullable)
 *     8. Survival Rate (nullable)
 *     9. T0 Density (nullable)
 *     10. Latest Live from latest observation (or across multiple observations if not all substrata
 *         are in the latest observation) (non-nullable)
 */
internal fun observationSpeciesTotalsMultiset(
    query:
        Select<
            Record10<
                RecordedSpeciesCertainty?,
                SpeciesId?,
                String?,
                Int?,
                Int?,
                Int?,
                Int?,
                Int?,
                BigDecimal?,
                Int?,
            >
        >
): Field<List<ObservationSpeciesResultsModel>> {
  return DSL.multiset(query).convertFrom { results ->
    results.map { record ->
      val certainty = record.value1()!!
      val speciesId = record.value2()
      val speciesName = record.value3()
      val totalLive = record.value4()!!
      val totalDead = record.value5()!!
      val totalExisting = record.value6()!!
      val permanentLive = record.value7()!!
      val survivalRate = record.value8()
      val t0Density = record.value9()
      val latestLive = record.value10()!!

      ObservationSpeciesResultsModel(
          certainty = certainty,
          latestLive = latestLive,
          permanentLive = permanentLive,
          speciesId = speciesId,
          speciesName = speciesName,
          survivalRate = survivalRate,
          t0Density = t0Density,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
          totalPlants = totalLive + totalExisting + totalDead,
      )
    }
  }
}

internal val monitoringPlotConditionsMultiset =
    DSL.multiset(
            DSL.select(OBSERVATION_PLOT_CONDITIONS.CONDITION_ID)
                .from(OBSERVATION_PLOT_CONDITIONS)
                .where(
                    OBSERVATION_PLOT_CONDITIONS.OBSERVATION_ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID)
                )
                .and(
                    OBSERVATION_PLOT_CONDITIONS.MONITORING_PLOT_ID.eq(
                        OBSERVATION_PLOTS.MONITORING_PLOT_ID
                    )
                )
        )
        .convertFrom { results ->
          results.map { record -> record[OBSERVATION_PLOT_CONDITIONS.CONDITION_ID]!! }.toSet()
        }

internal val monitoringPlotOverlappedByMultiset =
    DSL.multiset(
            DSL.select(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID)
                .from(MONITORING_PLOT_OVERLAPS)
                .where(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID.eq(MONITORING_PLOTS.ID))
                .orderBy(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID)
        )
        .convertFrom { results ->
          results.map { record -> record[MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID]!! }.toSet()
        }

internal val monitoringPlotOverlapsMultiset =
    DSL.multiset(
            DSL.select(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID)
                .from(MONITORING_PLOT_OVERLAPS)
                .where(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                .orderBy(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID)
        )
        .convertFrom { results ->
          results.map { record -> record[MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID]!! }.toSet()
        }

internal val monitoringPlotSpeciesMultiset =
    with(OBSERVED_PLOT_SPECIES_TOTALS) {
      observationSpeciesTotalsMultiset(
          DSL.select(
                  DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                  DSL.coalesce(
                      SPECIES_ID,
                      PLOT_T0_DENSITIES.SPECIES_ID,
                      STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                  ),
                  SPECIES_NAME,
                  DSL.coalesce(TOTAL_LIVE, 0),
                  DSL.coalesce(TOTAL_DEAD, 0),
                  DSL.coalesce(TOTAL_EXISTING, 0),
                  DSL.coalesce(PERMANENT_LIVE, 0),
                  DSL.coalesce(
                      SURVIVAL_RATE,
                      DSL.`when`(
                          PLOT_T0_DENSITIES.PLOT_DENSITY.isNotNull.or(
                              STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY.isNotNull
                          ),
                          DSL.inline(BigDecimal.ZERO),
                      ),
                  ),
                  DSL.case_()
                      .`when`(
                          OBSERVATION_PLOTS.IS_PERMANENT,
                          PLOT_T0_DENSITIES.PLOT_DENSITY,
                      )
                      .`when`(
                          MONITORING_PLOTS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)
                              .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(false)),
                          STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY,
                      )
                      .else_(null as BigDecimal?),
                  DSL.coalesce(TOTAL_LIVE, 0),
              )
              .from(OBSERVED_PLOT_SPECIES_TOTALS)
              // full outer join because we want survival rate to be 0 if a species wasn't
              // observed but has t0 density data set
              .fullOuterJoin(PLOT_T0_DENSITIES)
              .on(
                  PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID)
                      .and(PLOT_T0_DENSITIES.SPECIES_ID.eq(SPECIES_ID))
                      .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(true))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .fullOuterJoin(STRATUM_T0_TEMP_DENSITIES)
              .on(
                  STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                          OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories
                              .stratumHistories
                              .STRATUM_ID
                      )
                      .and(STRATUM_T0_TEMP_DENSITIES.SPECIES_ID.eq(SPECIES_ID))
                      .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(false))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .where(
                  MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID)
                      .or(
                          OBSERVATION_PLOTS.IS_PERMANENT.eq(true)
                              .and(PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                      )
                      .or(
                          MONITORING_PLOT_ID.isNull
                              .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(false))
                              .and(
                                  STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                                      OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories
                                          .stratumHistories
                                          .STRATUM_ID
                                  )
                              )
                      )
              )
              .and(OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed))
              .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
              .orderBy(SPECIES_ID, SPECIES_NAME)
      )
    }

internal val recordedPlantsGpsField = RECORDED_PLANTS.GPS_COORDINATES.forMultiset()

internal val recordedPlantsMultiset =
    DSL.multiset(
            DSL.select(
                    RECORDED_PLANTS.ID,
                    RECORDED_PLANTS.CERTAINTY_ID,
                    RECORDED_PLANTS.SPECIES_ID,
                    RECORDED_PLANTS.SPECIES_NAME,
                    RECORDED_PLANTS.STATUS_ID,
                    recordedPlantsGpsField,
                )
                .from(RECORDED_PLANTS)
                .where(RECORDED_PLANTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .and(RECORDED_PLANTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                .orderBy(RECORDED_PLANTS.ID)
        )
        .convertFrom { results ->
          results.map { record ->
            RecordedPlantModel(
                certainty = record[RECORDED_PLANTS.CERTAINTY_ID.asNonNullable()],
                gpsCoordinates = record[recordedPlantsGpsField.asNonNullable()] as Point,
                id = record[RECORDED_PLANTS.ID.asNonNullable()],
                speciesId = record[RECORDED_PLANTS.SPECIES_ID],
                speciesName = record[RECORDED_PLANTS.SPECIES_NAME],
                status = record[RECORDED_PLANTS.STATUS_ID.asNonNullable()],
            )
          }
        }

internal val monitoringPlotsBoundaryField = MONITORING_PLOTS.BOUNDARY.forMultiset()

internal fun substratumSpeciesMultiset(): Field<List<ObservationSpeciesResultsModel>> {
  val permanentSubstratumT0 =
      with(PLOT_T0_DENSITIES) {
        DSL.select(
                MONITORING_PLOT_HISTORIES.SUBSTRATUM_ID,
                SPECIES_ID,
                DSL.sum(PLOT_DENSITY).`as`("plot_density"),
            )
            .from(this)
            .join(MONITORING_PLOT_HISTORIES)
            .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
            .where(plotHasCompletedObservations(MONITORING_PLOT_ID, true))
            .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, true).isNotNull)
            .and(MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .groupBy(MONITORING_PLOT_HISTORIES.SUBSTRATUM_ID, SPECIES_ID)
            .asTable()
      }

  val tempSubstratumT0 =
      with(MONITORING_PLOT_HISTORIES) {
        DSL.select(
                SUBSTRATUM_ID,
                STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                DSL.sum(STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY).`as`("plot_density"),
            )
            .from(this)
            .join(STRATUM_T0_TEMP_DENSITIES)
            .on(
                STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                    substratumHistories.stratumHistories.STRATUM_ID
                )
            )
            .where(plotHasCompletedObservations(MONITORING_PLOT_ID, false))
            .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, false).isNotNull)
            .and(PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .and(plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
            .groupBy(SUBSTRATUM_ID, STRATUM_T0_TEMP_DENSITIES.SPECIES_ID)
            .asTable()
      }

  val permSubstratumCol =
      permanentSubstratumT0.field(
          "substratum_id",
          SQLDataType.BIGINT.asConvertedDataType(SubstratumIdConverter()),
      )!!
  val permSpeciesCol =
      permanentSubstratumT0.field(
          "species_id",
          SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
      )!!
  val permDensityCol = permanentSubstratumT0.field("plot_density", BigDecimal::class.java)!!

  val tempSubstratumCol =
      tempSubstratumT0.field(
          "substratum_id",
          SQLDataType.BIGINT.asConvertedDataType(SubstratumIdConverter()),
      )!!
  val tempSpeciesCol =
      tempSubstratumT0.field(
          "species_id",
          SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
      )!!
  val tempDensityCol = tempSubstratumT0.field("plot_density", BigDecimal::class.java)!!

  return with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
    observationSpeciesTotalsMultiset(
        DSL.select(
                DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                DSL.coalesce(SPECIES_ID, permSpeciesCol, tempSpeciesCol),
                SPECIES_NAME,
                DSL.coalesce(TOTAL_LIVE, 0),
                DSL.coalesce(TOTAL_DEAD, 0),
                DSL.coalesce(TOTAL_EXISTING, 0),
                DSL.coalesce(PERMANENT_LIVE, 0),
                DSL.coalesce(
                    SURVIVAL_RATE,
                    DSL.`when`(
                        DSL.coalesce(permDensityCol, BigDecimal.ZERO)
                            .plus(DSL.coalesce(tempDensityCol, BigDecimal.ZERO))
                            .gt(BigDecimal.ZERO),
                        BigDecimal.ZERO,
                    ),
                ),
                DSL.coalesce(permDensityCol.plus(tempDensityCol), permDensityCol, tempDensityCol),
                DSL.coalesce(TOTAL_LIVE, 0),
            )
            .from(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
            // full outer join because we want survival rate to be 0 if a species wasn't observed
            // but has t0 density data set
            .fullOuterJoin(permanentSubstratumT0)
            .on(
                permSubstratumCol
                    .eq(SUBSTRATUM_ID)
                    .and(permSpeciesCol.eq(SPECIES_ID))
                    .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
            )
            .fullOuterJoin(tempSubstratumT0)
            .on(
                tempSubstratumCol
                    .eq(SUBSTRATUM_ID)
                    .and(tempSpeciesCol.eq(SPECIES_ID))
                    .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
            )
            .where(
                SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID)
                    .or(permSubstratumCol.eq(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID))
                    .or(tempSubstratumCol.eq(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID))
            )
            .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
            .orderBy(SPECIES_ID, SPECIES_NAME)
    )
  }
}

internal fun stratumSpeciesMultiset(): Field<List<ObservationSpeciesResultsModel>> {
  val stratumHistoryAlias = STRATUM_HISTORIES.`as`("stratum_histories_2")
  val permStratumT0 =
      with(PLOT_T0_DENSITIES) {
        DSL.select(
                stratumHistoryAlias.STRATUM_ID,
                SPECIES_ID,
                DSL.sum(PLOT_DENSITY).`as`("plot_density"),
            )
            .from(this)
            .join(MONITORING_PLOT_HISTORIES)
            .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
            .join(SUBSTRATUM_HISTORIES)
            .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
            .join(stratumHistoryAlias)
            .on(stratumHistoryAlias.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
            .where(plotHasCompletedObservations(MONITORING_PLOT_ID, true))
            .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, true).isNotNull)
            .and(MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .groupBy(stratumHistoryAlias.STRATUM_ID, SPECIES_ID)
            .asTable()
      }

  val tempStratumT0 =
      with(MONITORING_PLOT_HISTORIES) {
        DSL.select(
                substratumHistories.stratumHistories.STRATUM_ID,
                STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                DSL.sum(STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY).`as`("plot_density"),
            )
            .from(this)
            .join(SUBSTRATUM_HISTORIES)
            .on(SUBSTRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORY_ID))
            .join(STRATUM_T0_TEMP_DENSITIES)
            .on(
                STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                    substratumHistories.stratumHistories.STRATUM_ID
                )
            )
            .where(plotHasCompletedObservations(MONITORING_PLOT_ID, false))
            .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, false).isNotNull)
            .and(PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .and(plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
            .groupBy(
                substratumHistories.stratumHistories.STRATUM_ID,
                STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
            )
            .asTable()
      }

  val permStratumCol =
      permStratumT0.field(
          "stratum_id",
          SQLDataType.BIGINT.asConvertedDataType(StratumIdConverter()),
      )!!
  val permSpeciesCol =
      permStratumT0.field(
          "species_id",
          SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
      )!!
  val permDensityCol = permStratumT0.field("plot_density", BigDecimal::class.java)!!

  val tempStratumCol =
      tempStratumT0.field(
          "stratum_id",
          SQLDataType.BIGINT.asConvertedDataType(StratumIdConverter()),
      )!!
  val tempSpeciesCol =
      tempStratumT0.field(
          "species_id",
          SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
      )!!
  val tempDensityCol = tempStratumT0.field("plot_density", BigDecimal::class.java)!!

  val latestLiveField =
      with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
        DSL.field(
            DSL.select(DSL.sum(DSL.coalesce(TOTAL_LIVE, 0)).cast(SQLDataType.INTEGER))
                .from(SUBSTRATA)
                .join(this)
                .on(SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                .where(
                    SUBSTRATA.STRATUM_ID.eq(OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID)
                        .and(SPECIES_ID.eq(OBSERVED_STRATUM_SPECIES_TOTALS.SPECIES_ID))
                )
                .and(
                    OBSERVATION_ID.eq(
                        latestObservationForSubstratumField(
                            OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID,
                            SUBSTRATUM_ID,
                        )
                    )
                )
        )
      }

  return with(OBSERVED_STRATUM_SPECIES_TOTALS) {
    observationSpeciesTotalsMultiset(
        DSL.select(
                DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                DSL.coalesce(SPECIES_ID, permSpeciesCol, tempSpeciesCol),
                SPECIES_NAME,
                DSL.coalesce(TOTAL_LIVE, 0),
                DSL.coalesce(TOTAL_DEAD, 0),
                DSL.coalesce(TOTAL_EXISTING, 0),
                DSL.coalesce(PERMANENT_LIVE, 0),
                DSL.coalesce(
                    SURVIVAL_RATE,
                    DSL.`when`(
                        DSL.coalesce(permDensityCol, BigDecimal.ZERO)
                            .plus(DSL.coalesce(tempDensityCol, BigDecimal.ZERO))
                            .gt(BigDecimal.ZERO),
                        BigDecimal.ZERO,
                    ),
                ),
                DSL.coalesce(permDensityCol.plus(tempDensityCol), permDensityCol, tempDensityCol),
                DSL.coalesce(latestLiveField, 0),
            )
            .from(OBSERVED_STRATUM_SPECIES_TOTALS)
            // full outer join because we want survival rate to be 0 if a species wasn't observed
            // but has t0 density data set
            .fullOuterJoin(permStratumT0)
            .on(
                permStratumCol
                    .eq(STRATUM_ID)
                    .and(permSpeciesCol.eq(SPECIES_ID))
                    .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
            )
            .fullOuterJoin(tempStratumT0)
            .on(
                tempStratumCol
                    .eq(STRATUM_ID)
                    .and(tempSpeciesCol.eq(SPECIES_ID))
                    .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
            )
            .where(
                STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID)
                    .or(permStratumCol.eq(STRATUM_HISTORIES.STRATUM_ID))
                    .or(tempStratumCol.eq(STRATUM_HISTORIES.STRATUM_ID))
            )
            .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
            .orderBy(SPECIES_ID, SPECIES_NAME)
    )
  }
}

internal val stratumPlantingCompletedField =
    DSL.field(
        DSL.notExists(
            DSL.selectOne()
                .from(SUBSTRATA)
                .where(SUBSTRATA.STRATUM_ID.eq(STRATUM_HISTORIES.STRATUM_ID))
                .and(
                    SUBSTRATA.PLANTING_COMPLETED_TIME.gt(OBSERVATIONS.COMPLETED_TIME)
                        .or(SUBSTRATA.PLANTING_COMPLETED_TIME.isNull)
                )
        )
    )

internal fun plantingSiteSpeciesMultiset(): Field<List<ObservationSpeciesResultsModel>> {
  val permSiteT0 =
      with(PLOT_T0_DENSITIES) {
        DSL.select(
                MONITORING_PLOT_HISTORIES.PLANTING_SITE_ID,
                SPECIES_ID,
                DSL.sum(PLOT_DENSITY).`as`("plot_density"),
            )
            .from(this)
            .join(MONITORING_PLOT_HISTORIES)
            .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
            .where(plotHasCompletedObservations(MONITORING_PLOT_ID, true))
            .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, true).isNotNull)
            .and(MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .groupBy(MONITORING_PLOT_HISTORIES.PLANTING_SITE_ID, SPECIES_ID)
            .asTable()
      }

  val tempSiteT0 =
      with(MONITORING_PLOT_HISTORIES) {
        DSL.select(
                plantingSiteHistories.plantingSites.ID.`as`("planting_site_id"),
                STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                DSL.sum(STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY).`as`("plot_density"),
            )
            .from(this)
            .join(STRATUM_T0_TEMP_DENSITIES)
            .on(
                STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                    substratumHistories.stratumHistories.STRATUM_ID
                )
            )
            .where(plotHasCompletedObservations(MONITORING_PLOT_ID, false))
            .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, false).isNotNull)
            .and(PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .and(plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
            .groupBy(
                plantingSiteHistories.plantingSites.ID,
                STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
            )
            .asTable()
      }

  val permSiteCol =
      permSiteT0.field(
          "planting_site_id",
          SQLDataType.BIGINT.asConvertedDataType(PlantingSiteIdConverter()),
      )!!
  val permSpeciesCol =
      permSiteT0.field(
          "species_id",
          SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
      )!!
  val permDensityCol = permSiteT0.field("plot_density", BigDecimal::class.java)!!

  val tempSiteCol =
      tempSiteT0.field(
          "planting_site_id",
          SQLDataType.BIGINT.asConvertedDataType(PlantingSiteIdConverter()),
      )!!
  val tempSpeciesCol =
      tempSiteT0.field(
          "species_id",
          SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
      )!!
  val tempDensityCol = tempSiteT0.field("plot_density", BigDecimal::class.java)!!

  val latestLiveField =
      with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
        DSL.field(
            DSL.select(DSL.sum(DSL.coalesce(TOTAL_LIVE, 0)).cast(SQLDataType.INTEGER))
                .from(SUBSTRATA)
                .join(this)
                .on(SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                .where(
                    SUBSTRATA.PLANTING_SITE_ID.eq(OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID)
                        .and(
                            SPECIES_ID.eq(OBSERVED_SITE_SPECIES_TOTALS.SPECIES_ID)
                                .or(
                                    SPECIES_ID.isNull
                                        .and(OBSERVED_SITE_SPECIES_TOTALS.SPECIES_ID.isNull)
                                        .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
                                        .and(
                                            OBSERVED_SITE_SPECIES_TOTALS.CERTAINTY_ID.eq(
                                                RecordedSpeciesCertainty.Other
                                            )
                                        )
                                )
                        )
                )
                .and(
                    OBSERVATION_ID.eq(
                        latestObservationForSubstratumField(
                            OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID,
                            SUBSTRATUM_ID,
                        )
                    )
                )
        )
      }

  return with(OBSERVED_SITE_SPECIES_TOTALS) {
    observationSpeciesTotalsMultiset(
        DSL.select(
                DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                DSL.coalesce(SPECIES_ID, permSpeciesCol, tempSpeciesCol),
                SPECIES_NAME,
                DSL.coalesce(TOTAL_LIVE, 0),
                DSL.coalesce(TOTAL_DEAD, 0),
                DSL.coalesce(TOTAL_EXISTING, 0),
                DSL.coalesce(PERMANENT_LIVE, 0),
                DSL.coalesce(
                    SURVIVAL_RATE,
                    DSL.`when`(
                        DSL.coalesce(permDensityCol, BigDecimal.ZERO)
                            .plus(DSL.coalesce(tempDensityCol, BigDecimal.ZERO))
                            .gt(BigDecimal.ZERO),
                        BigDecimal.ZERO,
                    ),
                ),
                DSL.coalesce(permDensityCol.plus(tempDensityCol), permDensityCol, tempDensityCol),
                DSL.coalesce(latestLiveField, 0),
            )
            .from(OBSERVED_SITE_SPECIES_TOTALS)
            // full outer join because we want survival rate to be 0 if a species wasn't observed
            // but has t0 density data set
            .fullOuterJoin(permSiteT0)
            .on(
                permSiteCol
                    .eq(PLANTING_SITE_ID)
                    .and(permSpeciesCol.eq(SPECIES_ID))
                    .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
            )
            .fullOuterJoin(tempSiteT0)
            .on(
                tempSiteCol
                    .eq(PLANTING_SITE_ID)
                    .and(tempSpeciesCol.eq(SPECIES_ID))
                    .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
            )
            .where(
                PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID)
                    .or(permSiteCol.eq(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID))
                    .or(tempSiteCol.eq(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID))
            )
            .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
            .orderBy(SPECIES_ID, SPECIES_NAME)
    )
  }
}
