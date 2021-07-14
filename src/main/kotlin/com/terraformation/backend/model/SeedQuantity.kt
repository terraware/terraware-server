package com.terraformation.backend.model

import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.services.equalsIgnoreScale
import java.math.BigDecimal

private val ouncesPerGram = BigDecimal("0.035274")
private val poundsPerGram = BigDecimal("0.00220462")
private val gramsPerOunce = BigDecimal("28.3495")
private val gramsPerPound = gramsPerOunce * BigDecimal(16)

fun SeedQuantityUnits.toGrams(quantity: BigDecimal): BigDecimal {
  return when (this) {
    SeedQuantityUnits.Seeds ->
        throw java.lang.IllegalArgumentException("Cannot convert seed count to weight")
    SeedQuantityUnits.Grams -> quantity
    SeedQuantityUnits.Milligrams -> quantity.divide(BigDecimal(1000))
    SeedQuantityUnits.Kilograms -> quantity * BigDecimal(1000)
    SeedQuantityUnits.Ounces -> quantity * gramsPerOunce
    SeedQuantityUnits.Pounds -> quantity * gramsPerPound
  }
}

fun SeedQuantityUnits.fromGrams(quantity: BigDecimal): BigDecimal {
  return when (this) {
    SeedQuantityUnits.Seeds -> throw IllegalArgumentException("Cannot convert weight to seed count")
    SeedQuantityUnits.Grams -> quantity
    SeedQuantityUnits.Kilograms -> quantity.divide(BigDecimal(1000))
    SeedQuantityUnits.Milligrams -> quantity * BigDecimal(1000)
    SeedQuantityUnits.Ounces -> quantity * ouncesPerGram
    SeedQuantityUnits.Pounds -> quantity * poundsPerGram
  }
}

data class SeedQuantityModel(val quantity: BigDecimal, val units: SeedQuantityUnits) :
    Comparable<SeedQuantityModel> {
  val grams
    get() = if (units != SeedQuantityUnits.Seeds) units.toGrams(quantity) else null

  fun toUnits(newUnits: SeedQuantityUnits): SeedQuantityModel {
    return if (units == newUnits) {
      this
    } else {
      SeedQuantityModel(newUnits.fromGrams(units.toGrams(quantity)), newUnits)
    }
  }

  operator fun minus(other: SeedQuantityModel): SeedQuantityModel {
    return SeedQuantityModel(quantity - other.toUnits(units).quantity, units)
  }

  operator fun plus(other: SeedQuantityModel): SeedQuantityModel {
    return SeedQuantityModel(quantity + other.toUnits(units).quantity, units)
  }

  override fun compareTo(other: SeedQuantityModel): Int {
    return if (units == other.units) {
      quantity.compareTo(other.quantity)
    } else {
      units.toGrams(quantity).compareTo(other.units.toGrams(other.quantity))
    }
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
