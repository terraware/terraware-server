package com.terraformation.backend.search.field

import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.fromGrams
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Search field for columns with weights in arbitrary units. Supports unit conversions on search
 * criteria.
 *
 * Unlike other numeric fields, this field type supports fuzzy searches, but they are numerically
 * fuzzy, not textually fuzzy: each search value gets converted to a range that matches values that
 * would round to the search value. That is, if you do a fuzzy search for 10.3 kilograms, you'll get
 * back results where 10.25 <= kilograms < 10.35.
 */
class WeightField(
    override val fieldName: String,
    private val quantityField: Field<BigDecimal?>,
    private val unitsField: Field<SeedQuantityUnits?>,
    private val gramsField: Field<BigDecimal?>,
    private val desiredUnits: SeedQuantityUnits,
    override val table: SearchTable,
    override val localize: Boolean = true,
    override val exportable: Boolean = true,
) : SearchField {
  private val formatRegex = Regex("(\\d|\\d.*\\d)\\s*(\\D*)")
  private val numberFormats = ConcurrentHashMap<Locale, NumberFormat>()

  override val selectFields: List<Field<*>> =
      when (desiredUnits) {
        SeedQuantityUnits.Milligrams,
        SeedQuantityUnits.Kilograms,
        SeedQuantityUnits.Grams -> listOf(gramsField)
        SeedQuantityUnits.Ounces,
        SeedQuantityUnits.Pounds -> listOf(quantityField, unitsField)
        SeedQuantityUnits.Seeds -> noSeeds()
      }

  override val orderByField: Field<*>
    get() = gramsField

  override fun getConditions(fieldNode: FieldNode): List<Condition> {
    val hasNull = fieldNode.values.any { it == null }
    val quantityModels = fieldNode.values.map { fromString(it) }
    val nonNullQuantities = quantityModels.filterNotNull()

    val condition =
        when (fieldNode.type) {
          SearchFilterType.Exact -> {
            val nullCondition = if (hasNull) gramsField.isNull else null

            val exactMatchConditions =
                nonNullQuantities.map { quantityModel ->
                  val gramsCondition =
                      gramsField.eq(quantityModel.toUnits(SeedQuantityUnits.Grams).quantity)
                  if (desiredUnits != SeedQuantityUnits.Grams) {
                    DSL.or(
                        gramsCondition,
                        unitsField
                            .eq(quantityModel.units)
                            .and(quantityField.eq(quantityModel.quantity)),
                    )
                  } else {
                    gramsCondition
                  }
                }

            DSL.or(exactMatchConditions + listOfNotNull(nullCondition))
          }
          SearchFilterType.Range -> {
            if (
                quantityModels.size != 2 || quantityModels[0] == null && quantityModels[1] == null
            ) {
              throw IllegalArgumentException(
                  "Range search must have two values, one or both of which must be non-null"
              )
            }

            val gramsQuantities =
                quantityModels.map { it?.toUnits(SeedQuantityUnits.Grams)?.quantity }

            if (gramsQuantities[0] != null && gramsQuantities[1] != null) {
              gramsField.between(gramsQuantities[0], gramsQuantities[1])
            } else if (quantityModels[0] != null) {
              gramsField.ge(gramsQuantities[0])
            } else {
              gramsField.le(gramsQuantities[1])
            }
          }
          SearchFilterType.ExactOrFuzzy,
          SearchFilterType.Fuzzy -> {
            val nullCondition = if (hasNull) gramsField.isNull else null

            val rangeConditions =
                nonNullQuantities.map { quantityModel ->
                  // Range that rounds to the search value, e.g., 432.1 -> BETWEEN(432.05, 432.15)
                  val scale = quantityModel.quantity.scale()
                  val maxDifference =
                      SeedQuantityModel(BigDecimal(".5").movePointLeft(scale), quantityModel.units)
                  val lowerBound = quantityModel - maxDifference
                  val upperBound = quantityModel + maxDifference
                  val lowerGrams = lowerBound.toUnits(SeedQuantityUnits.Grams).quantity
                  val upperGrams = upperBound.toUnits(SeedQuantityUnits.Grams).quantity

                  gramsField.between(lowerGrams, upperGrams)
                }

            DSL.or(rangeConditions + listOfNotNull(nullCondition))
          }
          SearchFilterType.PhraseMatch ->
              throw IllegalArgumentException("Phrase match not supported for weights")
        }

    return listOf(condition)
  }

  private val numberFormat: NumberFormat
    get() =
        numberFormats.getOrPut(currentLocale()) {
          (NumberFormat.getNumberInstance(currentLocale()) as DecimalFormat).apply {
            isParseBigDecimal = true
            maximumFractionDigits = NumericSearchField.MAXIMUM_FRACTION_DIGITS
          }
        }

  private fun noSeeds(): Nothing {
    throw IllegalArgumentException("Weight fields cannot be measured in seeds")
  }

  private fun fromString(value: String?): SeedQuantityModel? {
    if (value == null) {
      return null
    }

    val matches =
        formatRegex.matchEntire(value)
            ?: throw IllegalStateException(
                "Weight values must be a decimal number optionally followed by a unit name; couldn't interpret $value"
            )

    val number =
        if (localize) {
          numberFormat.parseObject(matches.groupValues[1]) as BigDecimal
        } else {
          matches.groupValues[1].toBigDecimal()
        }
    val unitsName = matches.groupValues[2].lowercase().replaceFirstChar { it.titlecase() }

    val units =
        if (unitsName.isEmpty()) desiredUnits
        else SeedQuantityUnits.forDisplayName(unitsName, currentLocale())

    return SeedQuantityModel(number, units)
  }

  override fun computeValue(record: Record): String? {
    val quantity: BigDecimal =
        when (desiredUnits) {
          SeedQuantityUnits.Ounces,
          SeedQuantityUnits.Pounds ->
              SeedQuantityModel.of(record[quantityField], record[unitsField])
                  ?.toUnitsOrNull(desiredUnits)
                  ?.quantity
          else -> record[gramsField]?.let { desiredUnits.fromGrams(it) }
        } ?: return null

    return if (localize) {
      numberFormat.format(quantity.stripTrailingZeros())
    } else {
      quantity.toPlainString()
    }
  }

  override fun raw(): SearchField? {
    return if (localize) {
      WeightField(
          rawFieldName(),
          quantityField,
          unitsField,
          gramsField,
          desiredUnits,
          table,
          false,
          false,
      )
    } else {
      null
    }
  }
}
