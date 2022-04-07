package com.terraformation.backend.search.field

import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.seedbank.model.toGrams
import java.math.BigDecimal
import org.jooq.Record
import org.jooq.TableField

/** Search field for columns with weights in grams. Supports unit conversions on search criteria. */
class GramsField(
    fieldName: String,
    displayName: String,
    databaseField: TableField<*, BigDecimal?>,
    table: SearchTable,
) : NumericSearchField<BigDecimal>(fieldName, displayName, databaseField, table) {
  private val formatRegex = Regex("([\\d.]+)\\s*(\\D*)")

  override fun fromString(value: String): BigDecimal {
    val matches =
        formatRegex.matchEntire(value)
            ?: throw IllegalStateException(
                "Weight values must be a decimal number optionally followed by a unit name; couldn't interpret $value")

    val number = BigDecimal(matches.groupValues[1])
    val unitsName = matches.groupValues[2].lowercase().replaceFirstChar { it.titlecase() }

    val units =
        if (unitsName.isEmpty()) SeedQuantityUnits.Grams
        else SeedQuantityUnits.forDisplayName(unitsName)

    return units.toGrams(number)
  }

  override fun computeValue(record: Record) = record[databaseField]?.toPlainString()
}
