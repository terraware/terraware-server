package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_POPULATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class SubstratumPopulationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SUBSTRATUM_POPULATIONS.SUBSTRATUM_POPULATION_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species",
              SUBSTRATUM_POPULATIONS.SPECIES_ID.eq(SPECIES.ID),
          ),
          substrata.asSingleValueSublist(
              "plantingSubzone",
              SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField(
              "plantsSinceLastObservation",
              SUBSTRATUM_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION,
          ),
          integerField("totalPlants", SUBSTRATUM_POPULATIONS.TOTAL_PLANTS),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(SUBSTRATA)
        .on(SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
        .join(PLANTING_SITE_SUMMARIES)
        .on(SUBSTRATA.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
            SUBSTRATUM_POPULATIONS.SPECIES_ID,
        )
}
