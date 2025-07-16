package com.terraformation.backend.seedbank

import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.seedbank.api.SeedQuantityPayload
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import java.math.BigDecimal

inline fun <reified T> quantity(quantity: BigDecimal, units: SeedQuantityUnits): T {
  return when (T::class.java) {
    SeedQuantityPayload::class.java -> SeedQuantityPayload(quantity, units) as T
    else -> SeedQuantityModel(quantity, units) as T
  }
}

inline fun <reified T> quantity(quantity: Int, units: SeedQuantityUnits): T {
  return quantity(BigDecimal(quantity), units) as T
}

inline fun <reified T> grams(quantity: Int): T {
  return quantity(quantity, SeedQuantityUnits.Grams)
}

inline fun <reified T> milligrams(quantity: Int): T {
  return quantity(quantity, SeedQuantityUnits.Milligrams)
}

inline fun <reified T> milligrams(quantity: BigDecimal): T {
  return quantity(quantity, SeedQuantityUnits.Milligrams)
}

inline fun <reified T> kilograms(quantity: BigDecimal): T {
  return quantity(quantity, SeedQuantityUnits.Kilograms)
}

inline fun <reified T> kilograms(quantity: Int): T {
  return quantity(quantity, SeedQuantityUnits.Kilograms)
}

inline fun <reified T> seeds(quantity: Int): T {
  return quantity(quantity, SeedQuantityUnits.Seeds)
}
