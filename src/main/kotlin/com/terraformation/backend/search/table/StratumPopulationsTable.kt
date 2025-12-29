package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_POPULATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class StratumPopulationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = STRATUM_POPULATIONS.STRATUM_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species",
              STRATUM_POPULATIONS.SPECIES_ID.eq(SPECIES.ID),
          ),
          strata.asSingleValueSublist(
              "plantingZone",
              STRATUM_POPULATIONS.STRATUM_ID.eq(STRATA.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField(
              "plantsSinceLastObservation",
              STRATUM_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION,
          ),
          integerField("totalPlants", STRATUM_POPULATIONS.TOTAL_PLANTS),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(STRATA)
        .on(STRATUM_POPULATIONS.STRATUM_ID.eq(STRATA.ID))
        .join(PLANTING_SITE_SUMMARIES)
        .on(STRATA.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            STRATUM_POPULATIONS.STRATUM_ID,
            STRATUM_POPULATIONS.SPECIES_ID,
        )
}
