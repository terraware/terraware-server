package com.terraformation.backend.search.field

import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.seedbank.model.toGrams
import java.math.BigDecimal
import java.util.*
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/** Search field for columns with weights in grams. Supports unit conversions on search criteria. */
class GramsField(
    override val fieldName: String,
    override val displayName: String,
    override val databaseField: TableField<*, BigDecimal?>,
    override val table: SearchTable
) : SingleColumnSearchField<BigDecimal>() {
  override val supportedFilterTypes: Set<SearchFilterType> =
      EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

  override fun computeValue(record: Record) = record[databaseField]?.toPlainString()

  override fun getCondition(fieldNode: FieldNode): Condition {
    val bigDecimalValues = fieldNode.values.map { parseGrams(it) }
    val nonNullValues = bigDecimalValues.filterNotNull()

    return when (fieldNode.type) {
      SearchFilterType.Exact -> {
        DSL.or(
            listOfNotNull(
                if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                if (fieldNode.values.any { it == null }) databaseField.isNull else null))
      }
      SearchFilterType.Fuzzy ->
          throw RuntimeException("Fuzzy search not supported for numeric fields")
      SearchFilterType.Range -> rangeCondition(bigDecimalValues)
    }
  }

  private val formatRegex = Regex("([\\d.]+)\\s*(\\D*)")

  private fun parseGrams(value: String?): BigDecimal? {
    if (value == null) {
      return null
    }

    val matches =
        formatRegex.matchEntire(value)
            ?: throw IllegalStateException(
                "Weight values must be a decimal number optionally followed by a unit name; couldn't interpret $value")

    val number = BigDecimal(matches.groupValues[1])
    val unitsName = matches.groupValues[2].lowercase().replaceFirstChar { it.titlecase() }

    val units =
        if (unitsName.isEmpty()) SeedQuantityUnits.Grams
        else
            SeedQuantityUnits.forDisplayName(unitsName)
                ?: throw IllegalArgumentException("Unrecognized weight unit in $value")

    return units.toGrams(number)
  }
}
