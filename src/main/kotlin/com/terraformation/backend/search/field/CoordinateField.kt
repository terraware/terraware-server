package com.terraformation.backend.search.field

import com.terraformation.backend.db.GeometrySerializer
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry

/**
 * Search field for individual coordinate values from geometries. This is intended for simple
 * geometries such as monitoring plots whose points are in a known, fixed order.
 */
class CoordinateField(
    fieldName: String,
    private val geometryField: Field<Geometry?>,
    /** Which vertex of the geometry's exterior ring to return. 1-indexed. */
    private val vertexIndex: Int,
    private val axis: Axis,
    table: SearchTable,
    localize: Boolean,
    exportable: Boolean,
) :
    NumericSearchField<BigDecimal>(
        fieldName,
        coordinateExtractionField(geometryField, vertexIndex, axis),
        table,
        localize,
        exportable,
    ) {

  override val supportedFilterTypes: Set<SearchFilterType>
    get() = emptySet()

  override fun getCondition(fieldNode: FieldNode): Condition {
    throw IllegalArgumentException("Filters not supported for geometry fields")
  }

  override fun fromString(value: String) = numberFormat.parseObject(value) as BigDecimal

  override fun makeNumberFormat(): NumberFormat {
    return (NumberFormat.getNumberInstance(currentLocale()) as DecimalFormat).apply {
      isParseBigDecimal = true
      maximumFractionDigits = GeometrySerializer.COORDINATE_VALUE_SCALE
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      CoordinateField(
          fieldName = rawFieldName(),
          geometryField = geometryField,
          vertexIndex = vertexIndex,
          axis = axis,
          table = table,
          localize = false,
          exportable = false,
      )
    } else {
      null
    }
  }

  companion object {
    sealed interface Axis {
      /** The name of the PostGIS function that extracts this axis's value from a Point. */
      val functionName: String
    }

    data object LATITUDE : Axis {
      override val functionName = "ST_Y"
    }

    data object LONGITUDE : Axis {
      override val functionName = "ST_X"
    }

    /**
     * Returns a jOOQ Field that extracts a single coordinate value from a particular vertex of a
     * geometry. This is the jOOQ representation of nested calls to a few PostGIS functions, e.g.,
     * `ST_X(ST_PointN(ST_ExteriorRing(boundary), 2))`.
     */
    fun coordinateExtractionField(
        geometryField: Field<Geometry?>,
        vertexIndex: Int,
        axis: Axis,
    ): Field<BigDecimal?> {
      return DSL.function(
          axis.functionName,
          BigDecimal::class.java,
          DSL.function(
              "ST_PointN",
              Geometry::class.java,
              DSL.function("ST_ExteriorRing", Geometry::class.java, geometryField),
              DSL.value(vertexIndex),
          ),
      )
    }
  }
}
