package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

internal class SearchServiceCursorTest : SearchServiceTest() {
  @Test
  fun `can use cursor to get next page of results`() {
    val fields =
        listOf(speciesNameField, accessionNumberField, plantsCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expectedFirstPageResults =
        listOf(
            mapOf(
                "speciesName" to "Kousa Dogwood",
                "id" to "$accessionId1",
                "accessionNumber" to "XYZ",
                "plantsCollectedFrom" to "1",
                "active" to "Active",
            ),
        )

    val firstPage =
        searchAccessions(
            facilityId,
            fields,
            criteria = NoConditionNode(),
            sortOrder = sortOrder,
            limit = 1,
        )
    assertEquals(expectedFirstPageResults, firstPage.results)
    // We just care that we get a cursor, not what it is specifically
    assertNotNull(firstPage.cursor)

    val expectedSecondPage =
        SearchResults(
            listOf(
                mapOf(
                    "speciesName" to "Other Dogwood",
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "plantsCollectedFrom" to "2",
                    "active" to "Active",
                )
            )
        )

    val secondPage =
        searchAccessions(
            facilityId,
            fields,
            criteria = NoConditionNode(),
            sortOrder = sortOrder,
            cursor = firstPage.cursor,
            limit = 1,
        )
    assertEquals(expectedSecondPage, secondPage)
  }
}
