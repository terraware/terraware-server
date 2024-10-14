package com.terraformation.backend.search.field

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.table.MonitoringPlotsTable
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry

/**
 * Search field for individual waypoints values from geometries. This is intended for simple
 * geometries such as monitoring plots whose points are in a known, fixed order.
 */
class WaypointField(
  override val fieldName: String,
  /** Name of the waypoint. Used for writing to GPX file. */
  private val plotNameField: Field<String>?,
  private val geometryField: Field<Geometry?>,
  /** Which vertex of the geometry's exterior ring to return. 1-indexed. */
  private val vertexIndex: Int,
  /** Name suffix for the waypoint. Added after plot name*/
  private val waypointSuffix: String = "",
  override val table: SearchTable,
  override val localize: Boolean,
) : SearchField {

  override val supportedFilterTypes: Set<SearchFilterType> = emptySet()

  override val exportable: Boolean = false

  override val orderByField: Field<*> = DSL.value(vertexIndex)

  private val latitudeField = CoordinateField.coordinateExtractionField(geometryField, vertexIndex, CoordinateField.Companion.LATITUDE)
  private val longitudeField = CoordinateField.coordinateExtractionField(geometryField, vertexIndex, CoordinateField.Companion.LONGITUDE)

  override val selectFields: List<Field<*>> =
      listOfNotNull(
          plotNameField,
          latitudeField,
          longitudeField,
      )

  override fun raw(): SearchField? {
    return if (localize) {
      WaypointField(
          fieldName = rawFieldName(),
          plotNameField = plotNameField,
          geometryField = geometryField,
          vertexIndex = vertexIndex,
          waypointSuffix = waypointSuffix,
          table = table,
          localize = false,
      )
    } else {
      null
    }
  }

  override fun getConditions(fieldNode: FieldNode): List<Condition> {
    throw IllegalArgumentException("Filters not supported for geometry fields")
  }

  override fun computeValue(record: Record): String {
    val plotName = plotNameField?.let { record[it] } ?: ""
    val latitude = record[latitudeField]!!
    val longitude = record[longitudeField]!!
    return "<wpt lat=\"$latitude\" lon=\"$longitude\"><name>$plotName$waypointSuffix</name></wpt>"
  }
}
