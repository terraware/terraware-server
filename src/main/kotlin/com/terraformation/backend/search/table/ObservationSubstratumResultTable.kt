package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class ObservationSubstratumResultTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_SUBSTRATUM_HISTORY_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
          substrata.asSingleValueSublist(
              "substratum",
              OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
              isRequired = false,
          ),
          substratumHistories.asSingleValueSublist(
              "substratumHistory",
              OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID),
          ),
          observationPlotResult.asMultiValueSublist(
              "plotResults",
              OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(
                      OBSERVATION_PLOT_RESULTS.OBSERVATION_ID
                  )
                  .and(
                      DSL.exists(
                          DSL.selectOne()
                              .from(MONITORING_PLOT_HISTORIES)
                              .where(
                                  MONITORING_PLOT_HISTORIES.ID.eq(
                                      OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID
                                  )
                              )
                              .and(
                                  MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(
                                      OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID
                                  )
                              )
                      )
                  ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField("permanentLive", OBSERVATION_SUBSTRATUM_RESULTS.PERMANENT_LIVE),
          integerField("plantDensity", OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY),
          integerField("plantDensityStdDev", OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY_STD_DEV),
          integerField("survivalRate", OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE),
          integerField("survivalRateStdDev", OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE_STD_DEV),
          integerField("totalDead", OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_DEAD),
          integerField("totalExisting", OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_EXISTING),
          integerField("totalLive", OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID,
            OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID,
        )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(OBSERVATIONS)
        .on(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
