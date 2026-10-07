package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_POPULATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class SubstratumPopulationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SUBSTRATUM_POPULATIONS.SUBSTRATUM_POPULATION_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species",
              SUBSTRATUM_POPULATIONS.SPECIES_ID,
              SPECIES.ID,
          ),
          substrata.asSingleValueSublist(
              "plantingSubzone",
              SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
              SUBSTRATA.ID,
          ),
          substrata.asSingleValueSublist(
              "substratum",
              SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
              SUBSTRATA.ID,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField("totalPlants", SUBSTRATUM_POPULATIONS.TOTAL_PLANTS),
      )

  override val visibilitySublistName: String
    get() = "substratum"

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
            SUBSTRATUM_POPULATIONS.SPECIES_ID,
        )
}
