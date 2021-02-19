package com.terraformation.seedbank.search

import com.terraformation.seedbank.api.seedbank.SearchDirection
import com.terraformation.seedbank.api.seedbank.SearchRequestPayload
import com.terraformation.seedbank.api.seedbank.SearchResponsePayload
import com.terraformation.seedbank.api.seedbank.SearchSortOrderElement
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.DatabaseTest
import com.terraformation.seedbank.db.PostgresFuzzySearchOperators
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.tables.daos.AccessionDao
import com.terraformation.seedbank.db.tables.pojos.Accession
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class SearchServiceTest : DatabaseTest() {
  @Autowired private lateinit var config: TerrawareServerConfig
  private lateinit var accessionDao: AccessionDao
  private lateinit var searchService: SearchService

  private val searchFields = SearchFields(PostgresFuzzySearchOperators())
  private val accessionNumberField = searchFields["accessionNumber"]!!
  private val activeField = searchFields["active"]!!
  private val receivedDateField = searchFields["receivedDate"]!!
  private val speciesField = searchFields["species"]!!
  private val stateField = searchFields["state"]!!
  private val targetStorageConditionField = searchFields["targetStorageCondition"]!!
  private val treesCollectedFromField = searchFields["treesCollectedFrom"]!!

  @BeforeEach
  fun init() {
    accessionDao = AccessionDao(dslContext.configuration())
    searchService = SearchService(dslContext, searchFields)
  }

  @Test
  fun `finds example rows`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, activeField)
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
                    "active" to "Active",
                ),
                mapOf(
                    "species" to "Other Dogwood",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "active" to "Active",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `honors sort order`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, activeField)
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
                    "active" to "Active",
                ),
                mapOf(
                    "species" to "Kousa Dogwood",
                    "accessionNumber" to "XYZ",
                    "treesCollectedFrom" to "1",
                    "active" to "Active",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `sorts enum fields by display name rather than ID`() {
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(
            targetStorageCondition = StorageCondition.Freezer))
    accessionDao.update(
        accessionDao.fetchOneByNumber("XYZ")!!.copy(
            targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val sortFields =
        listOf(SearchSortOrderElement(targetStorageConditionField, SearchDirection.Descending))
    val criteria = SearchRequestPayload(fields = fields, sortOrder = sortFields)

    val result = searchService.search(criteria)

    val expected =
        SearchResponsePayload(
            listOf(
                mapOf("accessionNumber" to "XYZ", "targetStorageCondition" to "Refrigerator"),
                mapOf("accessionNumber" to "ABCDEFG", "targetStorageCondition" to "Freezer"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `uses enum display name when it differs from Kotlin name`() {
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(stateId = AccessionState.InStorage))

    val criteria =
        SearchRequestPayload(
            fields = listOf(stateField),
            filters = listOf(SearchFilter(stateField, listOf("In Storage"))))

    val result = searchService.search(criteria)

    val expected =
        SearchResponsePayload(
            listOf(
                mapOf("accessionNumber" to "ABCDEFG", "state" to "In Storage"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can use cursor to get next page of results`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, activeField)
    val sortFields = fields.map { SearchSortOrderElement(it) }
    val criteria = SearchRequestPayload(fields = fields, sortOrder = sortFields, count = 1)

    val expectedFirstPageResults =
        listOf(
            mapOf(
                "species" to "Kousa Dogwood",
                "accessionNumber" to "XYZ",
                "treesCollectedFrom" to "1",
                "active" to "Active",
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
                    "active" to "Active",
                ),
            ),
            cursor = null)

    val secondPage = searchService.search(criteria.copy(cursor = firstPage.cursor))
    assertEquals(expectedSecondPage, secondPage)
  }

  @Nested
  inner class DateFieldSearchTest {
    @BeforeEach
    fun insertReceivedDateExamples() {
      listOf(1, 2, 8).forEach { day ->
        accessionDao.insert(
            Accession(
                createdTime = Instant.now(),
                number = "JAN$day",
                siteModuleId = config.siteModuleId,
                stateId = AccessionState.Processing,
                receivedDate = LocalDate.of(2021, 1, day)))
      }
    }

    @Test
    fun `can search for exact date`() {
      val fields = listOf(accessionNumberField)
      val filters =
          listOf(SearchFilter(receivedDateField, listOf("2021-01-02"), SearchFilterType.Exact))
      val criteria = SearchRequestPayload(fields = fields, filters = filters)

      val expected = SearchResponsePayload(listOf(mapOf("accessionNumber" to "JAN2")), null)
      val actual = searchService.search(criteria)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search by date range`() {
      val fields = listOf(accessionNumberField)
      val sortFields = listOf(SearchSortOrderElement(receivedDateField))
      val filters =
          listOf(
              SearchFilter(
                  receivedDateField, listOf("2021-01-02", "2021-01-15"), SearchFilterType.Range))
      val criteria =
          SearchRequestPayload(fields = fields, sortOrder = sortFields, filters = filters)

      val expected =
          SearchResponsePayload(
              listOf(mapOf("accessionNumber" to "JAN2"), mapOf("accessionNumber" to "JAN8")), null)
      val actual = searchService.search(criteria)

      assertEquals(expected, actual)
    }

    @Test
    fun `malformed dates are rejected`() {
      val fields = listOf(accessionNumberField)
      val filters =
          listOf(SearchFilter(receivedDateField, listOf("NOT_A_DATE"), SearchFilterType.Exact))
      val criteria = SearchRequestPayload(fields = fields, filters = filters)

      assertThrows(IllegalArgumentException::class.java) { searchService.search(criteria) }
    }
  }

  @Test
  fun `fetchFieldValues with no criteria for simple column value`() {
    val values = searchService.fetchFieldValues(speciesField, emptyList())
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fetchFieldValues with fuzzy search of accession number`() {
    val values =
        searchService.fetchFieldValues(
            speciesField,
            listOf(SearchFilter(accessionNumberField, listOf("xyzz"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Kousa Dogwood"), values)
  }

  @Test
  fun `fetchFieldValues with prefix search of accession number`() {
    val values =
        searchService.fetchFieldValues(
            speciesField,
            listOf(SearchFilter(accessionNumberField, listOf("a"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Other Dogwood"), values)
  }

  @Test
  fun `fetchFieldValues with fuzzy search of text field in secondary table`() {
    val values =
        searchService.fetchFieldValues(
            speciesField,
            listOf(SearchFilter(speciesField, listOf("dogwod"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fetchFieldValues with exact search of integer column value`() {
    val values =
        searchService.fetchFieldValues(
            treesCollectedFromField, listOf(SearchFilter(treesCollectedFromField, listOf("1"))))

    assertEquals(listOf("1"), values)
  }

  @Test
  fun `fetchFieldValues with no criteria for computed column value`() {
    val values = searchService.fetchFieldValues(activeField, emptyList())
    assertEquals(listOf("Active"), values)
  }
}
