package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class ObservationBiomassDetailsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_BIOMASS_DETAILS.OBSERVATION_PLOT_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          observationPlots.asSingleValueSublist(
              "observationPlot",
              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_PLOTS.OBSERVATION_PLOT_ID
              ),
          ),
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
          observationBiomassSpecies.asMultiValueSublist(
              "species",
              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_BIOMASS_SPECIES.OBSERVATION_PLOT_ID
              ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("description", OBSERVATION_BIOMASS_DETAILS.DESCRIPTION),
          enumField("forestType", OBSERVATION_BIOMASS_DETAILS.FOREST_TYPE_ID),
          integerField(
              "herbaceousCoverPercent",
              OBSERVATION_BIOMASS_DETAILS.HERBACEOUS_COVER_PERCENT,
          ),
          integerField(
              "numPlants",
              DSL.field(
                  DSL.selectCount()
                      .from(RECORDED_TREES)
                      .where(
                          RECORDED_TREES.OBSERVATION_PLOT_ID.eq(
                              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_PLOT_ID
                          )
                      )
              ),
          ),
          integerField(
              "numSpecies",
              DSL.field(
                  DSL.select(DSL.countDistinct(RECORDED_TREES.BIOMASS_SPECIES_ID))
                      .from(RECORDED_TREES)
                      .where(
                          RECORDED_TREES.OBSERVATION_PLOT_ID.eq(
                              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_PLOT_ID
                          )
                      )
              ),
          ),
          bigDecimalField("ph", OBSERVATION_BIOMASS_DETAILS.PH),
          integerField("smallTreesCountHigh", OBSERVATION_BIOMASS_DETAILS.SMALL_TREES_COUNT_HIGH),
          integerField("smallTreesCountLow", OBSERVATION_BIOMASS_DETAILS.SMALL_TREES_COUNT_LOW),
          bigDecimalField("salinity", OBSERVATION_BIOMASS_DETAILS.SALINITY_PPT),
          textField("soilAssessment", OBSERVATION_BIOMASS_DETAILS.SOIL_ASSESSMENT),
          enumField("tide", OBSERVATION_BIOMASS_DETAILS.TIDE_ID),
          timestampField("tideTime", OBSERVATION_BIOMASS_DETAILS.TIDE_TIME),
          integerField("waterDepth", OBSERVATION_BIOMASS_DETAILS.WATER_DEPTH_CM),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID,
            OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID,
        )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(OBSERVATIONS)
        .on(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
