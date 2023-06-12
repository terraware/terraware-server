package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSubzonesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SUBZONES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantings.asMultiValueSublist(
              "plantings", PLANTING_SUBZONES.ID.eq(PLANTINGS.PLANTING_SUBZONE_ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite", PLANTING_SUBZONES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)),
          plantingZones.asSingleValueSublist(
              "plantingZone", PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID)),
          plantingSubzonePopulations.asMultiValueSublist(
              "populations",
              PLANTING_SUBZONES.ID.eq(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID)),
          monitoringPlots.asMultiValueSublist(
              "monitoringPlots", PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_SUBZONES.BOUNDARY),
          timestampField("createdTime", PLANTING_SUBZONES.CREATED_TIME, nullable = false),
          textField("fullName", PLANTING_SUBZONES.FULL_NAME, nullable = false),
          idWrapperField("id", PLANTING_SUBZONES.ID) { PlantingSubzoneId(it) },
          timestampField("modifiedTime", PLANTING_SUBZONES.MODIFIED_TIME, nullable = false),
          textField("name", PLANTING_SUBZONES.NAME, nullable = false),
          timestampField(
              "plantingCompletedTime", PLANTING_SUBZONES.PLANTING_COMPLETED_TIME, nullable = false),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.plantingZones

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(PLANTING_ZONES).on(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return PLANTING_SUBZONES.plotsPlantingZoneIdFkey.plantingSites.ORGANIZATION_ID.eq(
        organizationId)
  }
}
