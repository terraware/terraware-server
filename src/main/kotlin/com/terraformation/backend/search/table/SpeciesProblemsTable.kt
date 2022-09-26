package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

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
          enumField("field", "Species problem field", SPECIES_PROBLEMS.FIELD_ID),
          idWrapperField("id", "Species problem ID", SPECIES_PROBLEMS.ID) { SpeciesProblemId(it) },
          textField(
              "suggestedValue",
              "Species problem suggested value",
              SPECIES_PROBLEMS.SUGGESTED_VALUE),
          enumField("type", "Species problem type", SPECIES_PROBLEMS.TYPE_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.species

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(SPECIES).on(SPECIES_PROBLEMS.SPECIES_ID.eq(SPECIES.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> SPECIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          SPECIES.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
