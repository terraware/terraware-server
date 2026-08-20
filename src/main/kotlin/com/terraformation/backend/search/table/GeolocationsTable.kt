package com.terraformation.backend.search.table

import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.GEOLOCATIONS
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.DatabaseFieldSupplier
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import com.terraformation.backend.search.field.columnSupplier
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

class GeolocationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = GEOLOCATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist("accession", GEOLOCATIONS.ACCESSION_ID, ACCESSIONS.ID),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          GeolocationField(
              "coordinates",
              columnSupplier(GEOLOCATIONS.LATITUDE),
              columnSupplier(GEOLOCATIONS.LONGITUDE),
              this,
          ),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(
      query: SelectJoinStep<T>,
      table: Table<*>,
  ): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(table.column(GEOLOCATIONS.ACCESSION_ID).eq(ACCESSIONS.ID))
  }

  /**
   * Search field for geolocation data. Geolocation is represented in search results as a single
   * string value that includes both latitude and longitude. But in the database, those two values
   * are stored as separate columns.
   */
  class GeolocationField(
      override val fieldName: String,
      private val getLatitudeField: DatabaseFieldSupplier<BigDecimal>,
      private val getLongitudeField: DatabaseFieldSupplier<BigDecimal>,
      override val table: SearchTable,
  ) : SearchField {
    private val latitudeField: Field<BigDecimal?> by lazy { getLatitudeField(table.fromTable) }
    private val longitudeField: Field<BigDecimal?> by lazy { getLongitudeField(table.fromTable) }

    override val localize: Boolean
      get() = false

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

    // Geolocation fields are always machine-readable.
    override fun raw(): SearchField? = null

    override fun withTable(newTable: SearchTable): SearchField {
      return GeolocationField(fieldName, getLatitudeField, getLongitudeField, newTable)
    }

    override fun toString() = fieldName

    override fun hashCode() = fieldName.hashCode()

    override fun equals(other: Any?) = other is GeolocationField && other.fieldName == fieldName
  }
}
