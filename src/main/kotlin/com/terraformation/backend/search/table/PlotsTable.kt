package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLOT_POPULATIONS
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

class PlotsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLOTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantings.asMultiValueSublist("plantings", PLOTS.ID.eq(PLANTINGS.PLOT_ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite", PLOTS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)),
          plantingZones.asSingleValueSublist(
              "plantingZone", PLOTS.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID)),
          plotPopulations.asMultiValueSublist("populations", PLOTS.ID.eq(PLOT_POPULATIONS.PLOT_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLOTS.BOUNDARY),
          timestampField("createdTime", PLOTS.CREATED_TIME, nullable = false),
          textField("fullName", PLOTS.FULL_NAME, nullable = false),
          idWrapperField("id", PLOTS.ID) { PlotId(it) },
          timestampField("modifiedTime", PLOTS.MODIFIED_TIME, nullable = false),
          textField("name", PLOTS.NAME, nullable = false),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.plantingZones

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(PLANTING_ZONES).on(PLOTS.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope ->
          PLOTS.plotsPlantingZoneIdFkey.plantingSites.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          PLOTS.plotsPlantingZoneIdFkey.plantingSites.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
