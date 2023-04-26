package com.terraformation.backend.search.field

import com.terraformation.backend.db.asGeoJson
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.locationtech.jts.geom.Geometry

/**
 * Search field for columns that hold geometry values. Geometry values are returned as GeoJSON
 * objects wrapped in strings.
 */
class GeometryField(
    override val fieldName: String,
    geometryField: TableField<*, Geometry?>,
    override val table: SearchTable,
    override val nullable: Boolean,
) : SingleColumnSearchField<String>() {
  override val supportedFilterTypes: Set<SearchFilterType>
    get() = emptySet()

  override val databaseField: Field<String?> = geometryField.asGeoJson()

  override fun getCondition(fieldNode: FieldNode): Condition {
    throw IllegalArgumentException("Filters not supported for geometry fields")
  }

  override fun computeValue(record: Record): String? {
    return record[databaseField]
  }

  // Geometry values are already machine-readable.
  override fun raw(): SearchField? = null
}
