package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SearchServiceGramsSearchTest : SearchServiceTest() {
  @Test
  fun `can specify weight units when searching by grams`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneById(AccessionId(1001))!!
            .copy(
                processingMethodId = ProcessingMethod.Weight,
                totalGrams = BigDecimal(1000),
                totalQuantity = BigDecimal(1),
                totalUnitsId = SeedQuantityUnits.Kilograms))

    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(
            totalGramsField,
            listOf("900000 Milligrams", "650000.000001 Pounds"),
            SearchFilterType.Range)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    val result = searchAccessions(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field defaults to grams if no units explicitly specified`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneById(AccessionId(1001))!!
            .copy(
                processingMethodId = ProcessingMethod.Weight,
                totalGrams = BigDecimal(1000),
                totalQuantity = BigDecimal(1),
                totalUnitsId = SeedQuantityUnits.Kilograms))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(totalGramsField, listOf("1000"))

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    val result = searchAccessions(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field throws exception for unknown units name`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(totalGramsField, listOf("1000 baseballs"))

    assertThrows<IllegalArgumentException> { searchAccessions(facilityId, fields, searchNode) }
  }
}
