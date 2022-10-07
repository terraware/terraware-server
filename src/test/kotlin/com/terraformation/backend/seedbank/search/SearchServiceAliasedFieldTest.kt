package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceAliasedFieldTest : SearchServiceTest() {
  @Test
  fun `can query both an alias field and its target`() {
    val fields = listOf(bagNumberField, bagNumberFlattenedField)
    val sortOrder = fields.map { SearchSortField(it) }

    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "B"))

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1000",
                    "bagNumber" to "A",
                    "bags_number" to "A",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "1000",
                    "bagNumber" to "B",
                    "bags_number" to "B",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `searching an aliased field returns results using the alias name`() {
    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFromAlias" to "2",
                ),
                mapOf(
                    "id" to "1000",
                    "accessionNumber" to "XYZ",
                    "treesCollectedFromAlias" to "1",
                ),
            ),
            cursor = null)

    val actual =
        searchAccessions(facilityId, listOf(treesCollectedFromAlias), criteria = NoConditionNode())
    assertEquals(expected, actual)
  }

  @Test
  fun `can use aliased field in search criteria`() {
    val criteria = FieldNode(treesCollectedFromAlias, listOf("2"))

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
            ),
            cursor = null)

    val actual = searchAccessions(facilityId, emptyList(), criteria = criteria)
    assertEquals(expected, actual)
  }

  @Test
  fun `can sort by aliased field that is not in select list`() {
    val sortOrder = listOf(SearchSortField(treesCollectedFromAlias))

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1000", "accessionNumber" to "XYZ"),
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
            ),
            cursor = null)

    val actual =
        searchAccessions(
            facilityId, emptyList(), criteria = NoConditionNode(), sortOrder = sortOrder)
    assertEquals(expected, actual)
  }
}
