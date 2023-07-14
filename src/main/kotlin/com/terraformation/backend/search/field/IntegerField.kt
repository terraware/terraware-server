package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import org.jooq.Field

/** Search field for numeric columns that don't allow fractional values. */
class IntegerField(
    fieldName: String,
    databaseField: Field<Int?>,
    table: SearchTable,
    nullable: Boolean = true,
    localize: Boolean = true,
) : NumericSearchField<Int>(fieldName, databaseField, table, nullable, localize) {
  override fun fromString(value: String) =
      if (localize) numberFormat.parse(value).toInt() else value.toInt()
  override fun makeNumberFormat(): NumberFormat = NumberFormat.getIntegerInstance(currentLocale())

  override fun raw(): SearchField? {
    return if (localize) {
      IntegerField(rawFieldName(), databaseField, table, nullable, false)
    } else {
      null
    }
  }
}
