package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.tables.references.SPECIES_PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class NurserySpeciesProjectsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SPECIES_PROJECTS.NURSERY_SPECIES_PROJECT_ID

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(SPECIES_PROJECTS.SPECIES_ID, SPECIES_PROJECTS.PROJECT_ID)

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist("project", SPECIES_PROJECTS.PROJECT_ID.eq(PROJECTS.ID)),
          species.asSingleValueSublist("species", SPECIES_PROJECTS.SPECIES_ID.eq(SPECIES.ID)),
          organizations.asSingleValueSublist(
              "organization",
              SPECIES_PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> = emptyList()

  override fun conditionForVisibility(): Condition {
    return SPECIES_PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }
}
