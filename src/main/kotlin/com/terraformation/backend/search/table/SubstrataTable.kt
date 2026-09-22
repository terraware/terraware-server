package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_POPULATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

class SubstrataTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SUBSTRATA.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asMultiValueSublist(
              "monitoringPlots",
              SUBSTRATA.ID,
              MONITORING_PLOTS.SUBSTRATUM_ID,
          ),
          plantings.asMultiValueSublist(
              "plantings",
              SUBSTRATA.ID,
              PLANTINGS.SUBSTRATUM_ID,
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              SUBSTRATA.PLANTING_SITE_ID,
              PLANTING_SITE_SUMMARIES.ID,
          ),
          strata.asSingleValueSublist(
              "plantingZone",
              SUBSTRATA.STRATUM_ID,
              STRATA.ID,
          ),
          strata.asSingleValueSublist(
              "stratum",
              SUBSTRATA.STRATUM_ID,
              STRATA.ID,
          ),
          substratumHistories.asMultiValueSublist(
              "histories",
              SUBSTRATA.ID,
              SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
          ),
          substratumPopulations.asMultiValueSublist(
              "populations",
              SUBSTRATA.ID,
              SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", SUBSTRATA.BOUNDARY),
          timestampField("createdTime", SUBSTRATA.CREATED_TIME),
          textField("fullName", SUBSTRATA.FULL_NAME),
          idWrapperField("id", SUBSTRATA.ID) { SubstratumId(it) },
          timestampField("modifiedTime", SUBSTRATA.MODIFIED_TIME),
          textField("name", SUBSTRATA.NAME),
          timestampField("observedTime", SUBSTRATA.OBSERVED_TIME),
          timestampField("plantingCompletedTime", SUBSTRATA.PLANTING_COMPLETED_TIME),
          bigDecimalField(
              "totalPlants",
              DSL.field(
                  DSL.select(DSL.sum(SUBSTRATUM_POPULATIONS.TOTAL_PLANTS))
                      .from(SUBSTRATUM_POPULATIONS)
                      .where(SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
              ),
          ),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.strata

  override fun <T : Record> joinForVisibility(
      query: SelectJoinStep<T>,
      table: Table<*>,
  ): SelectJoinStep<T> {
    return query.join(STRATA).on(table.column(SUBSTRATA.STRATUM_ID).eq(STRATA.ID))
  }
}
