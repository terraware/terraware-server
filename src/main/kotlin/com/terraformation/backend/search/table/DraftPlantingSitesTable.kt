package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class DraftPlantingSitesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DRAFT_PLANTING_SITES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          users.asSingleValueSublist("createdBy", DRAFT_PLANTING_SITES.CREATED_BY.eq(USERS.ID)),
          users.asSingleValueSublist("modifiedBy", DRAFT_PLANTING_SITES.MODIFIED_BY.eq(USERS.ID)),
          organizations.asSingleValueSublist(
              "organization",
              DRAFT_PLANTING_SITES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
          projects.asSingleValueSublist(
              "project",
              DRAFT_PLANTING_SITES.PROJECT_ID.eq(PROJECTS.ID),
              isRequired = false,
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
          integerField("numPlantingSubzones", DRAFT_PLANTING_SITES.NUM_SUBSTRATA),
          integerField("numPlantingZones", DRAFT_PLANTING_SITES.NUM_STRATA),
          integerField("numStrata", DRAFT_PLANTING_SITES.NUM_STRATA),
          integerField("numSubstrata", DRAFT_PLANTING_SITES.NUM_SUBSTRATA),
          zoneIdField("timeZone", DRAFT_PLANTING_SITES.TIME_ZONE),
      )

  override fun conditionForVisibility(): Condition {
    return DRAFT_PLANTING_SITES.ORGANIZATION_ID.`in`(
        currentUser().organizationRoles.filter { it.value != Role.Contributor }.keys
    )
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(DRAFT_PLANTING_SITES.ID)
}
