package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

internal class SearchServiceWeightTest : SearchServiceTest() {
  /** Sets accession 1's weight to the specified value and accession 2's to 1kg. */
  private fun setAccessionWeights(otherWeight: SeedQuantityModel = grams(800)) {
    accessionsDao.update(
        accessionsDao
            .fetchOneById(accessionId1)!!
            .copy(
                remainingGrams = otherWeight.grams,
                remainingQuantity = otherWeight.quantity,
                remainingUnitsId = otherWeight.units,
            )
    )
    accessionsDao.update(
        accessionsDao
            .fetchOneById(accessionId2)!!
            .copy(
                remainingGrams = BigDecimal(1000),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Kilograms,
            )
    )
  }

  @Test
  fun `can specify weight units when searching by grams`() {
    setAccessionWeights()

    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(
            remainingGramsField,
            listOf("900000 Milligrams", "650,000.000001 Pounds"),
            SearchFilterType.Range,
        )

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG")))

    val result = searchAccessions(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field defaults to grams if no units explicitly specified`() {
    setAccessionWeights()

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(remainingGramsField, listOf("1000"))

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG")))

    val result = searchAccessions(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on weight field throws exception for unknown units name`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(remainingGramsField, listOf("1000 baseballs"))

    assertThrows<IllegalArgumentException> { searchAccessions(facilityId, fields, searchNode) }
  }

  @Test
  fun `weight fields convert results to correct units`() {
    setAccessionWeights()

    val remainingKilogramsField = rootPrefix.resolve("remainingKilograms")
    val remainingMilligramsField = rootPrefix.resolve("remainingMilligrams")
    val remainingOuncesField = rootPrefix.resolve("remainingOunces")
    val remainingPoundsField = rootPrefix.resolve("remainingPounds")

    val fields =
        listOf(
            remainingKilogramsField,
            remainingMilligramsField,
            remainingOuncesField,
            remainingPoundsField,
        )
    val searchNode = FieldNode(remainingKilogramsField, listOf("1"))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "accessionNumber" to "ABCDEFG",
                    "id" to "$accessionId2",
                    "remainingKilograms" to "1",
                    "remainingMilligrams" to "1,000,000",
                    "remainingOunces" to "35.274",
                    "remainingPounds" to "2.20462",
                )
            )
        )

    val result = searchAccessions(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `fuzzy search on weight fields returns values that round to search term`() {
    setAccessionWeights(SeedQuantityModel(BigDecimal("2.20449"), SeedQuantityUnits.Pounds))

    val remainingPoundsField = rootPrefix.resolve("remainingPounds")

    assertAll(
        {
          val searchNode = FieldNode(remainingPoundsField, listOf("2.205"), SearchFilterType.Fuzzy)

          val expected =
              SearchResults(listOf(mapOf("accessionNumber" to "ABCDEFG", "id" to "$accessionId2")))

          val result = searchAccessions(facilityId, emptyList(), searchNode)

          assertEquals(expected, result, "Should exclude values that don't round to search term")
        },
        {
          val searchNode = FieldNode(remainingPoundsField, listOf("2.20"), SearchFilterType.Fuzzy)

          val expected =
              SearchResults(
                  listOf(
                      mapOf("accessionNumber" to "ABCDEFG", "id" to "$accessionId2"),
                      mapOf("accessionNumber" to "XYZ", "id" to "$accessionId1"),
                  )
              )

          val result = searchAccessions(facilityId, emptyList(), searchNode)

          assertEquals(expected, result, "Should include all values that round to search term")
        },
    )
  }
}
