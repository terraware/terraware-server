package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.math.RoundingMode

private val ouncesPerGram = BigDecimal("0.035274")
internal val ouncesPerPound = BigDecimal(16)
private val gramsPerOunce = BigDecimal("28.3495")
internal val gramsPerPound = gramsPerOunce * ouncesPerPound
private val poundsPerGram = BigDecimal("0.00220462")
internal val poundsPerOunce = BigDecimal("0.0625")

fun SeedQuantityUnits.toGrams(quantity: BigDecimal): BigDecimal {
  val value =
      when (this) {
        SeedQuantityUnits.Seeds ->
            throw java.lang.IllegalArgumentException("Cannot convert seed count to weight")
        SeedQuantityUnits.Grams -> quantity
        SeedQuantityUnits.Milligrams -> quantity.divide(BigDecimal(1000))
        SeedQuantityUnits.Kilograms -> quantity * BigDecimal(1000)
        SeedQuantityUnits.Ounces -> quantity * gramsPerOunce
        SeedQuantityUnits.Pounds -> quantity * gramsPerPound
      }

  return value.stripTrailingZeros()
}

fun SeedQuantityUnits.fromGrams(quantity: BigDecimal): BigDecimal {
  val value =
      when (this) {
        SeedQuantityUnits.Seeds ->
            throw IllegalArgumentException("Cannot convert weight to seed count")
        SeedQuantityUnits.Grams -> quantity
        SeedQuantityUnits.Kilograms -> quantity.divide(BigDecimal(1000))
        SeedQuantityUnits.Milligrams -> quantity * BigDecimal(1000)
        SeedQuantityUnits.Ounces -> quantity * ouncesPerGram
        SeedQuantityUnits.Pounds -> quantity * poundsPerGram
      }

  return value.stripTrailingZeros()
}

data class SeedQuantityModel(val quantity: BigDecimal, val units: SeedQuantityUnits) :
    Comparable<SeedQuantityModel> {
  val grams
    get() = if (units != SeedQuantityUnits.Seeds) units.toGrams(quantity) else null

  fun toUnits(
      newUnits: SeedQuantityUnits,
      subsetWeight: SeedQuantityModel? = null,
      subsetCount: Int? = null,
  ): SeedQuantityModel {
    return toUnitsOrNull(newUnits, subsetWeight, subsetCount)
        ?: throw IllegalArgumentException(
            "Cannot convert between weight and seed count without subset measurement"
        )
  }

  fun toUnitsOrNull(
      newUnits: SeedQuantityUnits,
      subsetWeight: SeedQuantityModel? = null,
      subsetCount: Int? = null,
  ): SeedQuantityModel? {
    return when {
      units == newUnits -> {
        this
      }
      units == SeedQuantityUnits.Pounds && newUnits == SeedQuantityUnits.Ounces -> {
        SeedQuantityModel(quantity * ouncesPerPound, SeedQuantityUnits.Ounces)
      }
      units == SeedQuantityUnits.Ounces && newUnits == SeedQuantityUnits.Pounds -> {
        SeedQuantityModel(quantity * poundsPerOunce, SeedQuantityUnits.Pounds)
      }
      units != SeedQuantityUnits.Seeds && newUnits != SeedQuantityUnits.Seeds -> {
        SeedQuantityModel(newUnits.fromGrams(units.toGrams(quantity)), newUnits)
      }
      subsetWeight == null || subsetCount == null -> {
        null
      }
      subsetWeight.quantity <= BigDecimal.ZERO -> {
        throw IllegalArgumentException("Subset weight must be above zero.")
      }
      newUnits == SeedQuantityUnits.Seeds -> {
        // Seed count must always be an integer, so we need to round the calculated quantity.
        val subsetInOurUnits = subsetWeight.toUnits(units)
        val seeds =
            quantity
                .times(subsetCount.toBigDecimal())
                .divide(subsetInOurUnits.quantity, 0, RoundingMode.HALF_UP)
        SeedQuantityModel(seeds, SeedQuantityUnits.Seeds)
      }
      else -> {
        // Limit calculated weights to 5 decimal places.
        val subsetInNewUnits = subsetWeight.toUnits(newUnits)
        val weight =
            quantity
                .times(subsetInNewUnits.quantity)
                .divide(subsetCount.toBigDecimal(), 5, RoundingMode.HALF_UP)
        SeedQuantityModel(weight, newUnits)
      }
    }
  }

  operator fun minus(other: SeedQuantityModel): SeedQuantityModel {
    return SeedQuantityModel(quantity - other.toUnits(units).quantity, units)
  }

  operator fun plus(other: SeedQuantityModel): SeedQuantityModel {
    return SeedQuantityModel(quantity + other.toUnits(units).quantity, units)
  }

  operator fun times(other: Int): SeedQuantityModel {
    return SeedQuantityModel(quantity.times(other.toBigDecimal()).stripTrailingZeros(), units)
  }

  override fun compareTo(other: SeedQuantityModel): Int {
    return if (units == other.units) {
      quantity.compareTo(other.quantity)
    } else {
      units.toGrams(quantity).compareTo(other.units.toGrams(other.quantity))
    }
  }

  fun abs(): SeedQuantityModel {
    return SeedQuantityModel(quantity.abs(), units)
  }

  /**
   * Compares two SeedQuantityModels without regard to the scales of their BigDecimal quantities.
   */
  override fun equals(other: Any?): Boolean {
    return other is SeedQuantityModel &&
        other.units == units &&
        other.quantity.equalsIgnoreScale(quantity)
  }

  override fun hashCode(): Int {
    return units.hashCode() xor quantity.stripTrailingZeros().hashCode()
  }

  companion object {
    @JvmStatic
    fun of(quantity: BigDecimal?, units: SeedQuantityUnits?): SeedQuantityModel? {
      return if (quantity != null && units != null) {
        SeedQuantityModel(quantity, units)
      } else {
        null
      }
    }
  }
}

/**
 * Compares two quantities for numeric and unit equality, ignoring differences in scale. That is, we
 * want `SeedQuantity(BigDecimal("1.5"), Grams)` to be considered equal to
 * `SeedQuantity(BigDecimal("1.50000"), Grams)`.
 *
 * Null quantities are considered equal to other null quantities but are never equal to non-null
 * ones.
 */
fun SeedQuantityModel?.equalsIgnoreScale(other: SeedQuantityModel?): Boolean {
  return when {
    this == null -> other == null
    other == null -> false
    else -> units == other.units && quantity.equalsIgnoreScale(other.quantity)
  }
}
