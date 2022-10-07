package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceEnumTest : SearchServiceTest() {
  @Test
  fun `sorts enum fields by display name rather than ID`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("ABCDEFG")!!
            .copy(targetStorageCondition = StorageCondition.Freezer))
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("XYZ")!!
            .copy(targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val sortOrder = listOf(SearchSortField(targetStorageConditionField, SearchDirection.Descending))

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1000",
                    "accessionNumber" to "XYZ",
                    "targetStorageCondition" to "Refrigerator"),
                mapOf(
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "targetStorageCondition" to "Freezer"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `uses enum display name when it differs from Kotlin name`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(stateId = AccessionState.InStorage))

    val searchNode = FieldNode(stateField, listOf("In Storage"))
    val fields = listOf(stateField)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG", "state" to "In Storage"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can search on enum in child table`() {
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            accessionId = AccessionId(1000),
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Seeds,
            testType = ViabilityTestType.Lab,
        ),
        ViabilityTestsRow(
            accessionId = AccessionId(1000),
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Seeds,
            testType = ViabilityTestType.Nursery,
        ),
        ViabilityTestsRow(
            accessionId = AccessionId(1001),
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Seeds,
            testType = ViabilityTestType.Lab,
        ),
    )

    val fields = listOf(viabilityTestsTypeField)
    val sortOrder =
        listOf(SearchSortField(accessionNumberField), SearchSortField(viabilityTestsTypeField))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001", "accessionNumber" to "ABCDEFG", "viabilityTests_type" to "Lab"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ", "viabilityTests_type" to "Lab"),
                mapOf(
                    "id" to "1000", "accessionNumber" to "XYZ", "viabilityTests_type" to "Nursery"),
            ),
            cursor = null)

    val actual =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)
    assertEquals(expected, actual)
  }
}
