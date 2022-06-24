package com.terraformation.backend.search.table

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class GeolocationsTable(
    private val tables: SearchTables,
    fuzzySearchOperators: FuzzySearchOperators
) : SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = GEOLOCATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist("accession", GEOLOCATIONS.ACCESSION_ID.eq(ACCESSIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          GeolocationField(
              "coordinates",
              "Geolocation coordinates",
              GEOLOCATIONS.LATITUDE,
              GEOLOCATIONS.LONGITUDE),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(GEOLOCATIONS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }

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
    override val table: SearchTable
      get() = this@GeolocationsTable

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
