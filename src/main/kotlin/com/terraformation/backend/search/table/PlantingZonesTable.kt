package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingZonesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = STRATA.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingZoneHistories.asMultiValueSublist(
              "histories",
              STRATA.ID.eq(STRATUM_HISTORIES.STRATUM_ID),
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              STRATA.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingSubzones.asMultiValueSublist(
              "plantingSubzones",
              STRATA.ID.eq(SUBSTRATA.STRATUM_ID),
          ),
          plantingZonePopulations.asMultiValueSublist(
              "populations",
              STRATA.ID.eq(STRATUM_POPULATIONS.STRATUM_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", STRATA.BOUNDARY),
          timestampField("boundaryModifiedTime", STRATA.BOUNDARY_MODIFIED_TIME),
          timestampField("createdTime", STRATA.CREATED_TIME),
          idWrapperField("id", STRATA.ID) { StratumId(it) },
          timestampField("modifiedTime", STRATA.MODIFIED_TIME),
          textField("name", STRATA.NAME),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(STRATA.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }
}
