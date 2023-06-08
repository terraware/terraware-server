package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingZonesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_ZONES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSites.asSingleValueSublist(
              "plantingSite", PLANTING_ZONES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)),
          plantingSubzones.asMultiValueSublist(
              "plantingSubzones", PLANTING_ZONES.ID.eq(PLANTING_SUBZONES.PLANTING_ZONE_ID)),
          plantingZonePopulations.asMultiValueSublist(
              "populations", PLANTING_ZONES.ID.eq(PLANTING_ZONE_POPULATIONS.PLANTING_ZONE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_ZONES.BOUNDARY),
          timestampField("createdTime", PLANTING_ZONES.CREATED_TIME, nullable = false),
          idWrapperField("id", PLANTING_ZONES.ID) { PlantingZoneId(it) },
          timestampField("modifiedTime", PLANTING_ZONES.MODIFIED_TIME, nullable = false),
          textField("name", PLANTING_ZONES.NAME, nullable = false),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_ZONES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return PLANTING_ZONES.plantingSites.ORGANIZATION_ID.eq(organizationId)
  }
}
