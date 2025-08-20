package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.search.SearchTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

internal class SearchServiceInitializationTest : SearchServiceTest() {
  @Test
  fun `tables initialize successfully`() {
    val visited = mutableSetOf<SearchTable>()
    val toVisit = mutableListOf<SearchTable>()

    toVisit.add(tables.organizations)

    while (toVisit.isNotEmpty()) {
      val table = toVisit.removeLast()

      assertDoesNotThrow("$table failed to initialize. Is it missing 'by lazy'?") {
        visited.add(table)

        // "map" has the side effect of making sure the list is initialized.
        toVisit.addAll(table.sublists.map { it.searchTable }.filter { it !in visited })

        table.fieldsWithVariants.forEach { _ ->
          // No-op; we just need to make sure we can iterate over the field list.
        }
      }
    }

    // Sanity-check that the test is actually walking the hierarchy
    assertTrue(visited.size > 5, "Should have checked more than ${visited.size} tables")
  }

  @Test
  fun `finds example rows`() {
    val fields =
        listOf(
            speciesNameField,
            accessionNumberField,
            plantsCollectedFromField,
            activeField,
        )
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "speciesName" to "Kousa Dogwood",
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "plantsCollectedFrom" to "1",
                    "active" to "Active",
                ),
                mapOf(
                    "speciesName" to "Other Dogwood",
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "plantsCollectedFrom" to "2",
                    "active" to "Active",
                ),
            )
        )

    assertEquals(expected, result)
  }
}
