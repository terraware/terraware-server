package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import org.jooq.Field

/** Search field for numeric columns that don't allow fractional values. */
class LongField(
    fieldName: String,
    databaseField: Field<Long?>,
    table: SearchTable,
    nullable: Boolean = true,
    localize: Boolean = true,
    exportable: Boolean = true,
) : NumericSearchField<Long>(fieldName, databaseField, table, nullable, localize, exportable) {
  override fun fromString(value: String) = numberFormat.parse(value).toLong()

  override fun makeNumberFormat(): NumberFormat = NumberFormat.getIntegerInstance(currentLocale())

  override fun raw(): SearchField? {
    return if (localize) {
      LongField(rawFieldName(), databaseField, table, nullable, false, false)
    } else {
      null
    }
  }
}
