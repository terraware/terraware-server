package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class PlantingSitesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SITES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization", PLANTING_SITES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          plantingZones.asMultiValueSublist(
              "plantingZones", PLANTING_SITES.ID.eq(PLANTING_ZONES.PLANTING_SITE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", "Planting site boundary", PLANTING_SITES.BOUNDARY),
          timestampField(
              "createdTime",
              "Planting site created time",
              PLANTING_SITES.CREATED_TIME,
              nullable = false),
          textField("description", "Planting site description", PLANTING_SITES.DESCRIPTION),
          idWrapperField("id", "Planting site ID", PLANTING_SITES.ID) { PlantingSiteId(it) },
          timestampField(
              "modifiedTime",
              "Planting site modified time",
              PLANTING_SITES.MODIFIED_TIME,
              nullable = false),
          textField("name", "Planting site name", PLANTING_SITES.NAME, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return PLANTING_SITES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> PLANTING_SITES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          PLANTING_SITES.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
