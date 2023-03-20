package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class PlantingSitesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SITE_SUMMARIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          deliveries.asMultiValueSublist(
              "deliveries", PLANTING_SITE_SUMMARIES.ID.eq(DELIVERIES.PLANTING_SITE_ID)),
          organizations.asSingleValueSublist(
              "organization", PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          plantingZones.asMultiValueSublist(
              "plantingZones", PLANTING_SITE_SUMMARIES.ID.eq(PLANTING_ZONES.PLANTING_SITE_ID)),
          plantingSitePopulations.asMultiValueSublist(
              "populations",
              PLANTING_SITE_SUMMARIES.ID.eq(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_SITE_SUMMARIES.BOUNDARY),
          timestampField("createdTime", PLANTING_SITE_SUMMARIES.CREATED_TIME, nullable = false),
          textField("description", PLANTING_SITE_SUMMARIES.DESCRIPTION),
          idWrapperField("id", PLANTING_SITE_SUMMARIES.ID) { PlantingSiteId(it) },
          timestampField("modifiedTime", PLANTING_SITE_SUMMARIES.MODIFIED_TIME, nullable = false),
          textField("name", PLANTING_SITE_SUMMARIES.NAME, nullable = false),
          longField("numPlantingZones", PLANTING_SITE_SUMMARIES.NUM_PLANTING_ZONES),
          longField("numPlantingSubzones", PLANTING_SITE_SUMMARIES.NUM_PLANTING_SUBZONES),
          zoneIdField("timeZone", PLANTING_SITE_SUMMARIES.TIME_ZONE),
      )

  override fun conditionForVisibility(): Condition {
    return PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PLANTING_SITE_SUMMARIES.ID)
}
