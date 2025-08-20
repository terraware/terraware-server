package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPath
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.search.field.AliasField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceAliasedFieldTest : SearchServiceTest() {
  @Test
  fun `can query both an alias field and its target`() {
    val fields = listOf(bagNumberField, bagNumberFlattenedField)
    val sortOrder = fields.map { SearchSortField(it) }

    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "B"))

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "bagNumber" to "A",
                    "bags_number" to "A",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "bagNumber" to "B",
                    "bags_number" to "B",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `searching an aliased field returns results using the alias name`() {
    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "plantsCollectedFromAlias" to "2",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "plantsCollectedFromAlias" to "1",
                ),
            )
        )

    val actual =
        searchAccessions(facilityId, listOf(plantsCollectedFromAlias), criteria = NoConditionNode())
    assertEquals(expected, actual)
  }

  @Test
  fun `raw variants of aliased fields use the alias name`() {
    val rawAlias =
        SearchFieldPath(rootPrefix, AliasField("alias", plantsCollectedFromField).raw()!!)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "alias(raw)" to "2",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "alias(raw)" to "1",
                ),
            )
        )

    val actual = searchAccessions(facilityId, listOf(rawAlias), criteria = NoConditionNode())
    assertEquals(expected, actual)
  }

  @Test
  fun `can use aliased field in search criteria`() {
    val criteria = FieldNode(plantsCollectedFromAlias, listOf("2"))

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG")))

    val actual = searchAccessions(facilityId, emptyList(), criteria = criteria)
    assertEquals(expected, actual)
  }

  @Test
  fun `can sort by aliased field that is not in select list`() {
    val sortOrder = listOf(SearchSortField(plantsCollectedFromAlias))

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG"),
            )
        )

    val actual =
        searchAccessions(
            facilityId,
            emptyList(),
            criteria = NoConditionNode(),
            sortOrder = sortOrder,
        )
    assertEquals(expected, actual)
  }
}
