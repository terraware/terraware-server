package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.seedbank.grams
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SeedQuantityModelTest {
  @Test
  fun `toUnits does correct weight conversions`() {
    val cases =
        listOf(
            SeedQuantityModel(BigDecimal.TEN, SeedQuantityUnits.Kilograms) to
                listOf(
                    SeedQuantityModel(BigDecimal("10"), SeedQuantityUnits.Kilograms),
                    SeedQuantityModel(BigDecimal("10000"), SeedQuantityUnits.Grams),
                    SeedQuantityModel(BigDecimal("10000000"), SeedQuantityUnits.Milligrams),
                    SeedQuantityModel(BigDecimal("22.0462"), SeedQuantityUnits.Pounds),
                    SeedQuantityModel(BigDecimal("352.74"), SeedQuantityUnits.Ounces),
                ),
            SeedQuantityModel(BigDecimal.TEN, SeedQuantityUnits.Ounces) to
                listOf(
                    SeedQuantityModel(BigDecimal("0.283495"), SeedQuantityUnits.Kilograms),
                    SeedQuantityModel(BigDecimal("283.495"), SeedQuantityUnits.Grams),
                    SeedQuantityModel(BigDecimal("283495"), SeedQuantityUnits.Milligrams),
                    SeedQuantityModel(BigDecimal("0.625"), SeedQuantityUnits.Pounds),
                    SeedQuantityModel(BigDecimal("10"), SeedQuantityUnits.Ounces),
                ),
            SeedQuantityModel(BigDecimal.TEN, SeedQuantityUnits.Pounds) to
                listOf(
                    SeedQuantityModel(BigDecimal("4.53592"), SeedQuantityUnits.Kilograms),
                    SeedQuantityModel(BigDecimal("4535.92"), SeedQuantityUnits.Grams),
                    SeedQuantityModel(BigDecimal("4535920"), SeedQuantityUnits.Milligrams),
                    SeedQuantityModel(BigDecimal("10"), SeedQuantityUnits.Pounds),
                    SeedQuantityModel(BigDecimal("160"), SeedQuantityUnits.Ounces),
                ),
        )

    cases.forEach { (original, conversions) ->
      conversions.forEach { expected ->
        assertEquals(
            expected,
            original.toUnits(expected.units),
            "${original.units} to ${expected.units}",
        )
      }
    }
  }

  @Test
  fun `toUnits throws exception for weight to seed conversion if no subset weight and count`() {
    assertThrows<IllegalArgumentException> {
      SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Grams)
          .toUnits(SeedQuantityUnits.Seeds, null, null)
    }
    assertThrows<IllegalArgumentException> {
      SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Grams)
          .toUnits(SeedQuantityUnits.Seeds, grams(1), null)
    }
    assertThrows<IllegalArgumentException> {
      SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Grams)
          .toUnits(SeedQuantityUnits.Seeds, null, 1)
    }
  }

  @Test
  fun `toUnits throws exception if subset weight quantity is zero`() {
    assertThrows<IllegalArgumentException> {
      SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Grams)
          .toUnits(SeedQuantityUnits.Seeds, grams(0), 1)
    }
  }

  @Test
  fun `toUnits throws exception for seed to weight conversion if no subset weight and count`() {
    assertThrows<IllegalArgumentException> {
      SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Seeds)
          .toUnits(SeedQuantityUnits.Grams, null, null)
    }
    assertThrows<IllegalArgumentException> {
      SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Seeds)
          .toUnits(SeedQuantityUnits.Grams, grams(1), null)
    }
    assertThrows<IllegalArgumentException> {
      SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Seeds)
          .toUnits(SeedQuantityUnits.Grams, null, 1)
    }
  }

  @Test
  fun `toUnits converts seeds to weight using subset weight and count`() {
    // Seeds are 3 grams each
    val subsetWeight = SeedQuantityModel(BigDecimal(9), SeedQuantityUnits.Grams)
    val subsetCount = 3

    val initial = SeedQuantityModel(BigDecimal(25), SeedQuantityUnits.Seeds)
    val converted = initial.toUnits(SeedQuantityUnits.Kilograms, subsetWeight, subsetCount)

    val expected = SeedQuantityModel(BigDecimal("0.075"), SeedQuantityUnits.Kilograms)
    assertEquals(expected, converted)
  }

  @Test
  fun `toUnits converts weight to seeds using subset weight and count`() {
    // Seeds are 2 grams each
    val subsetWeight = SeedQuantityModel(BigDecimal.TEN, SeedQuantityUnits.Grams)
    val subsetCount = 5

    val initial = SeedQuantityModel(BigDecimal(25), SeedQuantityUnits.Grams)
    val converted = initial.toUnits(SeedQuantityUnits.Seeds, subsetWeight, subsetCount)

    val expected = SeedQuantityModel(BigDecimal(13), SeedQuantityUnits.Seeds)
    assertEquals(expected, converted)
  }

  @Test
  fun `times strips trailing fractional zeros`() {
    val original = SeedQuantityModel(BigDecimal("0.05"), SeedQuantityUnits.Grams)
    val multiplied = original * 4

    assertEquals(2, original.quantity.scale(), "Original scale")
    assertEquals(1, multiplied.quantity.scale(), "Multiplied scale")
    assertEquals(
        SeedQuantityModel(BigDecimal("0.2"), SeedQuantityUnits.Grams),
        multiplied,
        "Multiplication result",
    )
  }
}
