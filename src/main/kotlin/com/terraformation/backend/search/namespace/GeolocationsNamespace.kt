package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.search.SearchTables
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class GeolocationsNamespace(private val searchTables: SearchTables) : SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      listOf(
          GeolocationField(
              "coordinates",
              "Geolocation coordinates",
              GEOLOCATIONS.LATITUDE,
              GEOLOCATIONS.LONGITUDE),
      )

  /**
   * Search field for geolocation data. Geolocation is represented in search results as a single
   * string value that includes both latitude and longitude. But in the database, those two values
   * are stored as separate columns.
   */
  inner class GeolocationField(
      override val fieldName: String,
      override val displayName: String,
      private val latitudeField: TableField<*, BigDecimal?>,
      private val longitudeField: TableField<*, BigDecimal?>,
      override val nullable: Boolean = true
  ) : SearchField {
    override val table
      get() = searchTables.geolocations

    override val supportedFilterTypes: Set<SearchFilterType>
      get() = emptySet()

    override val selectFields: List<Field<*>>
      get() = listOf(latitudeField, longitudeField)

    override val orderByField: Field<*>
      get() = DSL.jsonbArray(latitudeField, longitudeField)

    override fun getConditions(fieldNode: FieldNode): List<Condition> {
      throw IllegalArgumentException("Filters not supported for geolocation")
    }

    override fun computeValue(record: Record): String? {
      return record[latitudeField]?.let { latitude ->
        record[longitudeField]?.let { longitude ->
          "${latitude.toPlainString()}, ${longitude.toPlainString()}"
        }
      }
    }

    override fun toString() = fieldName
    override fun hashCode() = fieldName.hashCode()
    override fun equals(other: Any?) = other is GeolocationField && other.fieldName == fieldName
  }
}
