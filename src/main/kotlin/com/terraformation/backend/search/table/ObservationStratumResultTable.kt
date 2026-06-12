package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class ObservationStratumResultTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_STRATUM_RESULTS.OBSERVATION_STRATUM_HISTORY_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
          strata.asSingleValueSublist(
              "stratum",
              OBSERVATION_STRATUM_RESULTS.STRATUM_ID.eq(STRATA.ID),
              isRequired = false,
          ),
          stratumHistories.asSingleValueSublist(
              "stratumHistory",
              OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID),
              isRequired = false,
          ),
          observationSubstratumResult.asMultiValueSublist(
              "substratumResults",
              OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(
                      OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID
                  )
                  .and(
                      DSL.exists(
                          DSL.selectOne()
                              .from(SUBSTRATUM_HISTORIES)
                              .where(
                                  SUBSTRATUM_HISTORIES.ID.eq(
                                      OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID
                                  )
                              )
                              .and(
                                  SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(
                                      OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID
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
          integerField("observedDensity", OBSERVATION_STRATUM_RESULTS.OBSERVED_DENSITY),
          integerField("permanentLive", OBSERVATION_STRATUM_RESULTS.PERMANENT_LIVE),
          integerField("plantDensity", OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY),
          integerField("plantDensityStdDev", OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY_STD_DEV),
          integerField("survivalRate", OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE),
          integerField("survivalRateStdDev", OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE_STD_DEV),
          integerField("totalDead", OBSERVATION_STRATUM_RESULTS.TOTAL_DEAD),
          integerField("totalExisting", OBSERVATION_STRATUM_RESULTS.TOTAL_EXISTING),
          integerField("totalLive", OBSERVATION_STRATUM_RESULTS.TOTAL_LIVE),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID,
            OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID,
        )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(OBSERVATIONS)
        .on(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
