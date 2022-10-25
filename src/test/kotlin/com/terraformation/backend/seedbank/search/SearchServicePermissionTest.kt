package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServicePermissionTest : SearchServiceTest() {
  @Test
  fun `search only includes accessions at facilities the user has permission to view`() {
    // A facility in an org the user isn't in
    insertOrganization(2)
    insertFacility(2000)
    insertAccession(facilityId = 2000)

    val fields = listOf(accessionNumberField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ"),
            ),
            cursor = null)

    val actual =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `search returns empty result if user has no permission to view anything`() {
    every { user.facilityRoles } returns emptyMap()

    val fields = listOf(accessionNumberField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expected = SearchResults(emptyList(), cursor = null)

    val actual =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }
}
