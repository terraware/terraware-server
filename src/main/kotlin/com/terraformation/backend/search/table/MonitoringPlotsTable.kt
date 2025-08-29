package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.CoordinateField.Companion.LATITUDE
import com.terraformation.backend.search.field.CoordinateField.Companion.LONGITUDE
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class MonitoringPlotsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = MONITORING_PLOTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSites.asSingleValueSublist(
              "plantingSite",
              MONITORING_PLOTS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingSubzones.asSingleValueSublist(
              "plantingSubzone",
              MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID),
          ),
          observationPlots.asMultiValueSublist(
              "observationPlots",
              MONITORING_PLOTS.ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", MONITORING_PLOTS.BOUNDARY),
          timestampField("createdTime", MONITORING_PLOTS.CREATED_TIME),
          bigDecimalField("elevationMeters", MONITORING_PLOTS.ELEVATION_METERS),
          // For backward compatibility; remove once clients aren't searching this anymore.
          longField("fullName", MONITORING_PLOTS.PLOT_NUMBER),
          idWrapperField("id", MONITORING_PLOTS.ID) { MonitoringPlotId(it) },
          booleanField("isAdHoc", MONITORING_PLOTS.IS_AD_HOC),
          booleanField("isAvailable", MONITORING_PLOTS.IS_AVAILABLE),
          timestampField("modifiedTime", MONITORING_PLOTS.MODIFIED_TIME),
          // For backward compatibility; remove once clients aren't searching this anymore.
          longField("name", MONITORING_PLOTS.PLOT_NUMBER),
          coordinateField("northeastLatitude", MONITORING_PLOTS.BOUNDARY, NORTHEAST, LATITUDE),
          coordinateField("northeastLongitude", MONITORING_PLOTS.BOUNDARY, NORTHEAST, LONGITUDE),
          coordinateField("northwestLatitude", MONITORING_PLOTS.BOUNDARY, NORTHWEST, LATITUDE),
          coordinateField("northwestLongitude", MONITORING_PLOTS.BOUNDARY, NORTHWEST, LONGITUDE),
          longField("plotNumber", MONITORING_PLOTS.PLOT_NUMBER),
          integerField("sizeMeters", MONITORING_PLOTS.SIZE_METERS),
          coordinateField("southeastLatitude", MONITORING_PLOTS.BOUNDARY, SOUTHEAST, LATITUDE),
          coordinateField("southeastLongitude", MONITORING_PLOTS.BOUNDARY, SOUTHEAST, LONGITUDE),
          coordinateField("southwestLatitude", MONITORING_PLOTS.BOUNDARY, SOUTHWEST, LATITUDE),
          coordinateField("southwestLongitude", MONITORING_PLOTS.BOUNDARY, SOUTHWEST, LONGITUDE),
      )

  override fun conditionForVisibility(): Condition {
    return MONITORING_PLOTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
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
