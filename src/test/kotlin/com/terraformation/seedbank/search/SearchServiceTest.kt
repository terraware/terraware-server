package com.terraformation.seedbank.search

import com.terraformation.seedbank.api.seedbank.SearchDirection
import com.terraformation.seedbank.api.seedbank.SearchRequestPayload
import com.terraformation.seedbank.api.seedbank.SearchResponsePayload
import com.terraformation.seedbank.api.seedbank.SearchSortOrderElement
import com.terraformation.seedbank.db.DatabaseTest
import com.terraformation.seedbank.model.AccessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SearchServiceTest : DatabaseTest() {
  private lateinit var searchService: SearchService

  private val searchFields = SearchFields()
  private val speciesField = searchFields["species"]!!
  private val accessionNumberField = searchFields["accessionNumber"]!!
  private val treesCollectedFromField = searchFields["treesCollectedFrom"]!!
  private val statusField = searchFields["status"]!!

  @BeforeEach
  fun init() {
    searchService = SearchService(dslContext, searchFields)
  }

  @Test
  fun `finds example rows`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, statusField)
    val sortFields = fields.map { SearchSortOrderElement(it) }
    val criteria = SearchRequestPayload(fields = fields, sortOrder = sortFields)

    val result = searchService.search(criteria)

    val expected =
        SearchResponsePayload(
            listOf(
                mapOf(
                    "species" to "Kousa Dogwood",
                    "accessionNumber" to "XYZ",
                    "treesCollectedFrom" to "1",
                    "status" to "Active",
                ),
                mapOf(
                    "species" to "Other Dogwood",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "status" to "Active",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `honors sort order`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, statusField)
    val sortFields = fields.map { SearchSortOrderElement(it, SearchDirection.Descending) }
    val criteria = SearchRequestPayload(fields = fields, sortOrder = sortFields)

    val result = searchService.search(criteria)

    val expected =
        SearchResponsePayload(
            listOf(
                mapOf(
                    "species" to "Other Dogwood",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "status" to "Active",
                ),
                mapOf(
                    "species" to "Kousa Dogwood",
                    "accessionNumber" to "XYZ",
                    "treesCollectedFrom" to "1",
                    "status" to "Active",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can use cursor to get next page of results`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, statusField)
    val sortFields = fields.map { SearchSortOrderElement(it) }
    val criteria = SearchRequestPayload(fields = fields, sortOrder = sortFields, count = 1)

    val expectedFirstPageResults =
        listOf(
            mapOf(
                "species" to "Kousa Dogwood",
                "accessionNumber" to "XYZ",
                "treesCollectedFrom" to "1",
                "status" to "Active",
            ),
        )

    val firstPage = searchService.search(criteria)
    assertEquals(expectedFirstPageResults, firstPage.results)
    // We just care that we get a cursor, not what it is specifically
    assertNotNull(firstPage.cursor)

    val expectedSecondPage =
        SearchResponsePayload(
            listOf(
                mapOf(
                    "species" to "Other Dogwood",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "status" to "Active",
                ),
            ),
            cursor = null)

    val secondPage = searchService.search(criteria.copy(cursor = firstPage.cursor))
    assertEquals(expectedSecondPage, secondPage)
  }

  @Test
  fun `fetchFieldValues with no criteria for simple column value`() {
    val values = searchService.fetchFieldValues(speciesField, emptyList())
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fetchFieldValues with fuzzy search of text column value`() {
    val values =
        searchService.fetchFieldValues(
            speciesField,
            listOf(SearchFilter(accessionNumberField, listOf("Y"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Kousa Dogwood"), values)
  }

  @Test
  fun `fetchFieldValues with exact search of integer column value`() {
    val values =
        searchService.fetchFieldValues(
            treesCollectedFromField, listOf(SearchFilter(treesCollectedFromField, listOf("1"))))

    assertEquals(listOf(1), values)
  }

  @Test
  fun `fetchFieldValues with no criteria for computed column value`() {
    val values = searchService.fetchFieldValues(statusField, emptyList())
    assertEquals(listOf(AccessionStatus.Active), values)
  }
}
