package com.terraformation.seedbank.search

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.DatabaseTest
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.PostgresFuzzySearchOperators
import com.terraformation.seedbank.db.SpeciesEndangeredType
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.tables.daos.AccessionDao
import com.terraformation.seedbank.db.tables.daos.SpeciesDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationDao
import com.terraformation.seedbank.db.tables.pojos.Accession
import com.terraformation.seedbank.db.tables.pojos.Species
import com.terraformation.seedbank.db.tables.pojos.StorageLocation
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
  private val endangeredField = searchFields["endangered"]!!
  private val endangered2Field = searchFields["endangered2"]!!
  private val germinationTestTypeField = searchFields["germinationTestType"]!!
  private val receivedDateField = searchFields["receivedDate"]!!
  private val speciesField = searchFields["species"]!!
  private val stateField = searchFields["state"]!!
  private val storageLocationField = searchFields["storageLocation"]!!
  private val storageNotesField = searchFields["storageNotes"]!!
  private val targetStorageConditionField = searchFields["targetStorageCondition"]!!
  private val treesCollectedFromField = searchFields["treesCollectedFrom"]!!

  @BeforeEach
  fun init() {
    accessionDao = AccessionDao(dslContext.configuration())
    searchService = SearchService(dslContext, searchFields)

    insertSiteData()

    val now = Instant.now()

    val speciesDao = SpeciesDao(dslContext.configuration())
    speciesDao.insert(
        Species(id = 10000, name = "Kousa Dogwood", createdTime = now, modifiedTime = now))
    speciesDao.insert(
        Species(id = 10001, name = "Other Dogwood", createdTime = now, modifiedTime = now))

    accessionDao.insert(
        Accession(
            id = 1000,
            number = "XYZ",
            stateId = AccessionState.Processed,
            createdTime = now,
            siteModuleId = 100,
            speciesId = 10000,
            treesCollectedFrom = 1))
    accessionDao.insert(
        Accession(
            id = 1001,
            number = "ABCDEFG",
            stateId = AccessionState.Processing,
            createdTime = now,
            siteModuleId = 100,
            speciesId = 10001,
            treesCollectedFrom = 2))
  }

  @Test
  fun `finds example rows`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it) }

    val result = searchService.search(fields, sortOrder = sortOrder)

    val expected =
        SearchResults(
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
    val sortOrder = fields.map { SearchSortField(it, SearchDirection.Descending) }

    val result = searchService.search(fields, sortOrder = sortOrder)

    val expected =
        SearchResults(
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
  fun `can do range search on integer field`() {
    accessionDao.update(accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val filters =
        listOf(SearchFilter(treesCollectedFromField, listOf("2", "3000"), SearchFilterType.Range))

    val result = searchService.search(fields, filters)

    val expected =
        SearchResults(
            listOf(mapOf("accessionNumber" to "ABCDEFG", "treesCollectedFrom" to "500")),
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
    val sortOrder = listOf(SearchSortField(targetStorageConditionField, SearchDirection.Descending))

    val result = searchService.search(fields, sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf("accessionNumber" to "XYZ", "targetStorageCondition" to "Refrigerator"),
                mapOf("accessionNumber" to "ABCDEFG", "targetStorageCondition" to "Freezer"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can search for fake boolean fields`() {
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(
            speciesEndangeredTypeId = SpeciesEndangeredType.Yes))
    accessionDao.update(
        accessionDao.fetchOneByNumber("XYZ")!!.copy(
            speciesEndangeredTypeId = SpeciesEndangeredType.No))

    val fields = listOf(endangeredField, endangered2Field)
    val sortOrder = listOf(SearchSortField(endangeredField, SearchDirection.Descending))

    val result = searchService.search(fields, sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "accessionNumber" to "ABCDEFG", "endangered" to "true", "endangered2" to "Yes"),
                mapOf("accessionNumber" to "XYZ", "endangered" to "false", "endangered2" to "No"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `fake boolean fields map non-boolean values to null`() {
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(
            speciesEndangeredTypeId = SpeciesEndangeredType.Unsure))

    val fields = listOf(endangeredField, endangered2Field)
    val sortOrder = listOf(SearchSortField(endangeredField, SearchDirection.Descending))

    val result = searchService.search(fields, sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf("accessionNumber" to "ABCDEFG", "endangered2" to "Unsure"),
                mapOf("accessionNumber" to "XYZ"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `uses enum display name when it differs from Kotlin name`() {
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(stateId = AccessionState.InStorage))

    val filters = listOf(SearchFilter(stateField, listOf("In Storage")))
    val fields = listOf(stateField)

    val result = searchService.search(fields, filters)

    val expected =
        SearchResults(
            listOf(
                mapOf("accessionNumber" to "ABCDEFG", "state" to "In Storage"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `search leaves out null values`() {
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(
            targetStorageCondition = StorageCondition.Freezer))

    val fields = listOf(targetStorageConditionField)

    val result = searchService.search(fields)

    val expected =
        SearchResults(
            listOf(
                mapOf("accessionNumber" to "ABCDEFG", "targetStorageCondition" to "Freezer"),
                mapOf("accessionNumber" to "XYZ"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do exact search for null values`() {
    accessionDao.insert(
        Accession(
            createdTime = Instant.now(),
            number = "MISSING",
            siteModuleId = config.siteModuleId,
            stateId = AccessionState.Processing))
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(
            targetStorageCondition = StorageCondition.Freezer))
    accessionDao.update(
        accessionDao.fetchOneByNumber("XYZ")!!.copy(
            targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val filters = listOf(SearchFilter(targetStorageConditionField, listOf("Freezer", null)))

    val result = searchService.search(fields, filters)

    val expected =
        SearchResults(
            listOf(
                mapOf("accessionNumber" to "ABCDEFG", "targetStorageCondition" to "Freezer"),
                mapOf("accessionNumber" to "MISSING"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do fuzzy search for null values`() {
    accessionDao.insert(
        Accession(
            createdTime = Instant.now(),
            number = "MISSING",
            siteModuleId = config.siteModuleId,
            stateId = AccessionState.Processing))
    accessionDao.update(
        accessionDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "some matching notes"))
    accessionDao.update(accessionDao.fetchOneByNumber("XYZ")!!.copy(storageNotes = "not it"))

    val fields = listOf(accessionNumberField)
    val filters =
        listOf(SearchFilter(storageNotesField, listOf("matching", null), SearchFilterType.Fuzzy))

    val result = searchService.search(fields, filters)

    val expected =
        SearchResults(
            listOf(
                mapOf("accessionNumber" to "ABCDEFG"),
                mapOf("accessionNumber" to "MISSING"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can use cursor to get next page of results`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expectedFirstPageResults =
        listOf(
            mapOf(
                "species" to "Kousa Dogwood",
                "accessionNumber" to "XYZ",
                "treesCollectedFrom" to "1",
                "active" to "Active",
            ),
        )

    val firstPage = searchService.search(fields, sortOrder = sortOrder, limit = 1)
    assertEquals(expectedFirstPageResults, firstPage.results)
    // We just care that we get a cursor, not what it is specifically
    assertNotNull(firstPage.cursor)

    val expectedSecondPage =
        SearchResults(
            listOf(
                mapOf(
                    "species" to "Other Dogwood",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "active" to "Active",
                ),
            ),
            cursor = null)

    val secondPage =
        searchService.search(fields, sortOrder = sortOrder, cursor = firstPage.cursor, limit = 1)
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

      val expected = SearchResults(listOf(mapOf("accessionNumber" to "JAN2")), null)
      val actual = searchService.search(fields, filters)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search for missing date`() {
      val fields = listOf(accessionNumberField)
      val filters =
          listOf(
              SearchFilter(receivedDateField, listOf("2021-01-02", null), SearchFilterType.Exact))

      val expected =
          SearchResults(
              listOf(
                  mapOf("accessionNumber" to "ABCDEFG"),
                  mapOf("accessionNumber" to "JAN2"),
                  mapOf("accessionNumber" to "XYZ"),
              ),
              null)
      val actual = searchService.search(fields, filters)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search by date range`() {
      val fields = listOf(accessionNumberField)
      val sortOrder = listOf(SearchSortField(receivedDateField))
      val filters =
          listOf(
              SearchFilter(
                  receivedDateField, listOf("2021-01-02", "2021-01-15"), SearchFilterType.Range))

      val expected =
          SearchResults(
              listOf(mapOf("accessionNumber" to "JAN2"), mapOf("accessionNumber" to "JAN8")), null)
      val actual = searchService.search(fields, filters, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `malformed dates are rejected`() {
      val fields = listOf(accessionNumberField)
      val filters =
          listOf(SearchFilter(receivedDateField, listOf("NOT_A_DATE"), SearchFilterType.Exact))

      assertThrows(IllegalArgumentException::class.java) { searchService.search(fields, filters) }
    }
  }

  @Test
  fun `fetchValues with no criteria for simple column value`() {
    val values = searchService.fetchValues(speciesField, emptyList())
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fetchValues renders null values as null, not as a string`() {
    accessionDao.update(accessionDao.fetchOneByNumber("XYZ")!!.copy(speciesId = null))
    val values = searchService.fetchValues(speciesField, emptyList())
    assertEquals(listOf("Other Dogwood", null), values)
  }

  @Test
  fun `fetchValues with fuzzy search of accession number`() {
    val values =
        searchService.fetchValues(
            speciesField,
            listOf(SearchFilter(accessionNumberField, listOf("xyzz"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Kousa Dogwood"), values)
  }

  @Test
  fun `fetchValues with prefix search of accession number`() {
    val values =
        searchService.fetchValues(
            speciesField,
            listOf(SearchFilter(accessionNumberField, listOf("a"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Other Dogwood"), values)
  }

  @Test
  fun `fetchValues with fuzzy search of text field in secondary table`() {
    val values =
        searchService.fetchValues(
            speciesField,
            listOf(SearchFilter(speciesField, listOf("dogwod"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fetchValues with fuzzy search of text field in secondary table not in field list`() {
    val values =
        searchService.fetchValues(
            stateField,
            listOf(SearchFilter(speciesField, listOf("dogwod"), SearchFilterType.Fuzzy)))
    assertEquals(listOf("Processed", "Processing"), values)
  }

  @Test
  fun `fetchValues with exact search of integer column value`() {
    val values =
        searchService.fetchValues(
            treesCollectedFromField, listOf(SearchFilter(treesCollectedFromField, listOf("1"))))

    assertEquals(listOf("1"), values)
  }

  @Test
  fun `fetchValues with no criteria for computed column value`() {
    val values = searchService.fetchValues(activeField, emptyList())
    assertEquals(listOf("Active"), values)
  }

  @Test
  fun `fetchAllValues does not return null for non-nullable field`() {
    val values = searchService.fetchAllValues(accessionNumberField)
    assertFalse(values.any { it == null }, "List of values should not contain null")
  }

  @Test
  fun `fetchAllValues returns values for enum-mapped field`() {
    val expected = listOf(null) + GerminationTestType.values().map { it.displayName }
    val values = searchService.fetchAllValues(germinationTestTypeField)
    assertEquals(expected, values)
  }

  @Test
  fun `fetchAllValues returns values for free-text field on accession table`() {
    val expected = listOf(null, "Kousa Dogwood", "Other Dogwood")
    val values = searchService.fetchAllValues(speciesField)
    assertEquals(expected, values)
  }

  @Test
  fun `fetchAllValues returns values for field from reference table`() {
    val storageLocationDao = StorageLocationDao(dslContext.configuration())
    storageLocationDao.insert(
        StorageLocation(
            id = 1000,
            siteModuleId = 100,
            name = "Refrigerator 1",
            conditionId = StorageCondition.Refrigerator))
    storageLocationDao.insert(
        StorageLocation(
            id = 1001,
            siteModuleId = 100,
            name = "Freezer 1",
            conditionId = StorageCondition.Freezer))
    storageLocationDao.insert(
        StorageLocation(
            id = 1002,
            siteModuleId = 100,
            name = "Freezer 2",
            conditionId = StorageCondition.Freezer))

    val expected = listOf(null, "Freezer 1", "Freezer 2", "Refrigerator 1")
    val values = searchService.fetchAllValues(storageLocationField)
    assertEquals(expected, values)
  }
}
