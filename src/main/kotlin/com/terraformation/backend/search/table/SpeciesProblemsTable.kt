package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class SpeciesProblemsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SPECIES_PROBLEMS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist("species", SPECIES_PROBLEMS.SPECIES_ID.eq(SPECIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField("field", SPECIES_PROBLEMS.FIELD_ID),
          idWrapperField("id", SPECIES_PROBLEMS.ID) { SpeciesProblemId(it) },
          textField("suggestedValue", SPECIES_PROBLEMS.SUGGESTED_VALUE),
          enumField("type", SPECIES_PROBLEMS.TYPE_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.species

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(SPECIES).on(SPECIES_PROBLEMS.SPECIES_ID.eq(SPECIES.ID))
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return SPECIES.ORGANIZATION_ID.eq(organizationId)
  }
}
