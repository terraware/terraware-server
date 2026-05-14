package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class ObservationSiteResultTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_SITE_RESULTS.OBSERVATION_SITE_HISTORY_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingSiteHistories.asSingleValueSublist(
              "plantingSiteHistory",
              OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID),
          ),
          observationStratumResult.asMultiValueSublist(
              "stratumResults",
              OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID)
                  .and(
                      DSL.exists(
                          DSL.selectOne()
                              .from(STRATUM_HISTORIES)
                              .where(
                                  STRATUM_HISTORIES.ID.eq(
                                      OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID
                                  )
                              )
                              .and(
                                  STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                                      OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID
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
          integerField("permanentLive", OBSERVATION_SITE_RESULTS.PERMANENT_LIVE),
          integerField("plantDensity", OBSERVATION_SITE_RESULTS.PLANT_DENSITY),
          integerField("plantDensityStdDev", OBSERVATION_SITE_RESULTS.PLANT_DENSITY_STD_DEV),
          integerField("survivalRate", OBSERVATION_SITE_RESULTS.SURVIVAL_RATE),
          integerField("survivalRateStdDev", OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_STD_DEV),
          integerField("totalDead", OBSERVATION_SITE_RESULTS.TOTAL_DEAD),
          integerField("totalExisting", OBSERVATION_SITE_RESULTS.TOTAL_EXISTING),
          integerField("totalLive", OBSERVATION_SITE_RESULTS.TOTAL_LIVE),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(OBSERVATIONS).on(OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
