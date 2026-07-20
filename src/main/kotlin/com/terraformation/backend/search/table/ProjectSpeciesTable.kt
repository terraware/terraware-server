package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ProjectSpeciesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_SPECIES.PROJECT_SPECIES_KEY

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization",
              PROJECT_SPECIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
          projects.asSingleValueSublist(
              "project",
              PROJECT_SPECIES.PROJECT_ID.eq(PROJECTS.ID),
          ),
          species.asSingleValueSublist(
              "species",
              PROJECT_SPECIES.SPECIES_ID.eq(SPECIES.ID),
          ),
          users.asSingleValueSublist(
              "overriddenBy",
              PROJECT_SPECIES.OVERRIDDEN_BY.eq(USERS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField("calculatedNativity", PROJECT_SPECIES.CALCULATED_NATIVITY_ID),
          enumField(
              "calculatedNativitySourceDataset",
              PROJECT_SPECIES.CALCULATED_NATIVITY_DATASET_TYPE_ID,
          ),
          dateField(
              "calculatedNativitySourceDate",
              PROJECT_SPECIES.CALCULATED_NATIVITY_DATASET_DATE,
          ),
          textField("overriddenJustification", PROJECT_SPECIES.OVERRIDDEN_JUSTIFICATION),
          enumField("overriddenNativity", PROJECT_SPECIES.OVERRIDDEN_NATIVITY_ID),
          timestampField("overriddenTime", PROJECT_SPECIES.OVERRIDDEN_TIME),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.species

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(SPECIES).on(PROJECT_SPECIES.SPECIES_ID.eq(SPECIES.ID))
  }

  override val defaultOrderFields =
      listOf(
          PROJECT_SPECIES.ORGANIZATION_ID,
          PROJECT_SPECIES.PROJECT_ID,
          PROJECT_SPECIES.SPECIES_ID,
      )
}
