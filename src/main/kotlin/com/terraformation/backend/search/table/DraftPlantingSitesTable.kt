package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.DRAFT_STRATA
import com.terraformation.backend.db.tracking.tables.references.DRAFT_SUBSTRATA
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import com.terraformation.backend.search.field.columnsEqual
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

class DraftPlantingSitesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DRAFT_PLANTING_SITES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          users.asSingleValueSublist("createdBy", DRAFT_PLANTING_SITES.CREATED_BY, USERS.ID),
          users.asSingleValueSublist("modifiedBy", DRAFT_PLANTING_SITES.MODIFIED_BY, USERS.ID),
          organizations.asSingleValueSublist(
              "organization",
              DRAFT_PLANTING_SITES.ORGANIZATION_ID,
              ORGANIZATIONS.ID,
          ),
          projects.asSingleValueSublist(
              "project",
              DRAFT_PLANTING_SITES.PROJECT_ID,
              PROJECTS.ID,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", DRAFT_PLANTING_SITES.CREATED_TIME),
          textField("description", DRAFT_PLANTING_SITES.DESCRIPTION),
          idWrapperField("id", DRAFT_PLANTING_SITES.ID) { DraftPlantingSiteId(it) },
          timestampField("modifiedTime", DRAFT_PLANTING_SITES.MODIFIED_TIME),
          textField("name", DRAFT_PLANTING_SITES.NAME),
          integerField("numStrata") { table ->
            DSL.field(DSL.selectCount().from(DRAFT_STRATA).where(sameDraftPlantingSite(table)))
          },
          integerField("numSubstrata") { table ->
            DSL.field(
                DSL.selectCount()
                    .from(DRAFT_SUBSTRATA)
                    .join(DRAFT_STRATA)
                    .on(DRAFT_SUBSTRATA.DRAFT_STRATUM_ID.eq(DRAFT_STRATA.ID))
                    .where(sameDraftPlantingSite(table))
            )
          },
          zoneIdField("timeZone", DRAFT_PLANTING_SITES.TIME_ZONE),
      )

  override fun conditionForVisibility(table: Table<*>): Condition {
    return table
        .column(DRAFT_PLANTING_SITES.ORGANIZATION_ID)
        .`in`(
            currentUser()
                .organizationRoles
                .filter { it.value in setOf(Role.Admin, Role.Owner, Role.TerraformationContact) }
                .keys
        )
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(DRAFT_PLANTING_SITES.ID)

  private fun sameDraftPlantingSite(table: Table<*>) =
      columnsEqual(
          DRAFT_STRATA,
          DRAFT_STRATA.DRAFT_PLANTING_SITE_ID,
          table,
          DRAFT_PLANTING_SITES.ID,
      )
}
