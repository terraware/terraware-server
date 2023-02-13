package com.terraformation.backend.search.field

import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.SearchTable
import java.text.NumberFormat
import org.jooq.TableField

/** Search field for numeric columns that don't allow fractional values. */
class LongField(
    fieldName: String,
    databaseField: TableField<*, Long?>,
    table: SearchTable,
    nullable: Boolean = true,
    localize: Boolean = true,
) : NumericSearchField<Long>(fieldName, databaseField, table, nullable, localize) {
  override fun fromString(value: String) = numberFormat.parse(value).toLong()
  override fun makeNumberFormat(): NumberFormat = NumberFormat.getIntegerInstance(currentLocale())

  override fun raw(): SearchField? {
    return if (localize) {
      LongField("$fieldName(raw)", databaseField, table, nullable, false)
    } else {
      null
    }
  }
}
