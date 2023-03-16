package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
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

class MonitoringPlotsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = MONITORING_PLOTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSubzones.asSingleValueSublist(
              "plantingSubzone", MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", MONITORING_PLOTS.BOUNDARY),
          timestampField("createdTime", MONITORING_PLOTS.CREATED_TIME, nullable = false),
          textField("fullName", MONITORING_PLOTS.FULL_NAME, nullable = false),
          idWrapperField("id", MONITORING_PLOTS.ID) { MonitoringPlotId(it) },
          timestampField("modifiedTime", MONITORING_PLOTS.MODIFIED_TIME, nullable = false),
          textField("name", MONITORING_PLOTS.NAME, nullable = false),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.plantingSubzones

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SUBZONES)
        .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope ->
          MONITORING_PLOTS.plantingSubzones.plantingSites.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          MONITORING_PLOTS.plantingSubzones.plantingSites.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
