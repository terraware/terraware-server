package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.CoordinateField.Companion.LATITUDE
import com.terraformation.backend.search.field.CoordinateField.Companion.LONGITUDE
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

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
          timestampField("createdTime", MONITORING_PLOTS.CREATED_TIME),
          textField("fullName", MONITORING_PLOTS.FULL_NAME),
          idWrapperField("id", MONITORING_PLOTS.ID) { MonitoringPlotId(it) },
          timestampField("modifiedTime", MONITORING_PLOTS.MODIFIED_TIME),
          textField("name", MONITORING_PLOTS.NAME),
          coordinateField("northeastLatitude", MONITORING_PLOTS.BOUNDARY, NORTHEAST, LATITUDE),
          coordinateField("northeastLongitude", MONITORING_PLOTS.BOUNDARY, NORTHEAST, LONGITUDE),
          coordinateField("northwestLatitude", MONITORING_PLOTS.BOUNDARY, NORTHWEST, LATITUDE),
          coordinateField("northwestLongitude", MONITORING_PLOTS.BOUNDARY, NORTHWEST, LONGITUDE),
          integerField("sizeMeters", MONITORING_PLOTS.SIZE_METERS),
          coordinateField("southeastLatitude", MONITORING_PLOTS.BOUNDARY, SOUTHEAST, LATITUDE),
          coordinateField("southeastLongitude", MONITORING_PLOTS.BOUNDARY, SOUTHEAST, LONGITUDE),
          coordinateField("southwestLatitude", MONITORING_PLOTS.BOUNDARY, SOUTHWEST, LATITUDE),
          coordinateField("southwestLongitude", MONITORING_PLOTS.BOUNDARY, SOUTHWEST, LONGITUDE),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.plantingSubzones

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SUBZONES)
        .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
  }

  companion object {
    // Vertex indexes for monitoring plot corners. Monitoring plot boundaries always start at the
    // southwest corner and go counterclockwise.
    const val SOUTHWEST = 1
    const val SOUTHEAST = 2
    const val NORTHEAST = 3
    const val NORTHWEST = 4
  }
}
