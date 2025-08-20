package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_SUCCESSIONAL_GROUPS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class SpeciesSuccessionalGroupsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SPECIES_SUCCESSIONAL_GROUPS.SPECIES_SUCCESSIONAL_GROUP_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species",
              SPECIES_SUCCESSIONAL_GROUPS.SPECIES_ID.eq(SPECIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField("successionalGroup", SPECIES_SUCCESSIONAL_GROUPS.SUCCESSIONAL_GROUP_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.species

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(SPECIES).on(SPECIES_SUCCESSIONAL_GROUPS.SPECIES_ID.eq(SPECIES.ID))
  }
}
