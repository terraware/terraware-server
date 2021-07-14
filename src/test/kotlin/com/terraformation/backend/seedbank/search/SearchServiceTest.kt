package com.terraformation.backend.seedbank.search

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.PostgresFuzzySearchOperators
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.tables.daos.AccessionGerminationTestTypesDao
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.pojos.AccessionGerminationTestTypesRow
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
import java.math.BigDecimal
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
  private lateinit var accessionsDao: AccessionsDao
  private lateinit var accessionGerminationTestTypesDao: AccessionGerminationTestTypesDao
  private lateinit var searchService: SearchService

  private val searchFields = SearchFields(PostgresFuzzySearchOperators())
  private val accessionNumberField = searchFields["accessionNumber"]!!
  private val activeField = searchFields["active"]!!
  private val germinationTestTypeField = searchFields["germinationTestType"]!!
  private val receivedDateField = searchFields["receivedDate"]!!
  private val speciesField = searchFields["species"]!!
  private val stateField = searchFields["state"]!!
  private val storageLocationField = searchFields["storageLocation"]!!
  private val storageNotesField = searchFields["storageNotes"]!!
  private val targetStorageConditionField = searchFields["targetStorageCondition"]!!
  private val totalGramsField = searchFields["totalGrams"]!!
  private val treesCollectedFromField = searchFields["treesCollectedFrom"]!!
  private val viabilityTestTypeField = searchFields["viabilityTestType"]!!

  @BeforeEach
  fun init() {
    accessionsDao = AccessionsDao(dslContext.configuration())
    accessionGerminationTestTypesDao = AccessionGerminationTestTypesDao(dslContext.configuration())
    searchService = SearchService(dslContext, searchFields)

    insertSiteData()

    val now = Instant.now()

    val speciesDao = SpeciesDao(dslContext.configuration())
    speciesDao.insert(
        SpeciesRow(id = 10000, name = "Kousa Dogwood", createdTime = now, modifiedTime = now))
    speciesDao.insert(
        SpeciesRow(id = 10001, name = "Other Dogwood", createdTime = now, modifiedTime = now))

    accessionsDao.insert(
        AccessionsRow(
            id = 1000,
            number = "XYZ",
            stateId = AccessionState.Processed,
            createdTime = now,
            facilityId = 100,
            speciesId = 10000,
            treesCollectedFrom = 1))
    accessionsDao.insert(
        AccessionsRow(
            id = 1001,
            number = "ABCDEFG",
            stateId = AccessionState.Processing,
            createdTime = now,
            facilityId = 100,
            speciesId = 10001,
            treesCollectedFrom = 2))
  }

  @Test
  fun `finds example rows`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it) }

    val result = searchService.search(fields, criteria = NoConditionNode(), sortOrder = sortOrder)

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

    val result = searchService.search(fields, criteria = NoConditionNode(), sortOrder = sortOrder)

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
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf("2", "3000"), SearchFilterType.Range)

    val result = searchService.search(fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("accessionNumber" to "ABCDEFG", "treesCollectedFrom" to "500")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no minimum`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf(null, "3"), SearchFilterType.Range)

    val result = searchService.search(fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("accessionNumber" to "XYZ", "treesCollectedFrom" to "1")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no maximum`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf("2", null), SearchFilterType.Range)

    val result = searchService.search(fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("accessionNumber" to "ABCDEFG", "treesCollectedFrom" to "500")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `range search on integer field with two nulls is rejected`() {
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf(null, null), SearchFilterType.Range)

    assertThrows(IllegalArgumentException::class.java) { searchService.search(fields, searchNode) }
  }

  @Test
  fun `sorts enum fields by display name rather than ID`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(
            targetStorageCondition = StorageCondition.Freezer))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("XYZ")!!.copy(
            targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val sortOrder = listOf(SearchSortField(targetStorageConditionField, SearchDirection.Descending))

    val result = searchService.search(fields, criteria = NoConditionNode(), sortOrder = sortOrder)

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
  fun `uses enum display name when it differs from Kotlin name`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(stateId = AccessionState.InStorage))

    val searchNode = FieldNode(stateField, listOf("In Storage"))
    val fields = listOf(stateField)

    val result = searchService.search(fields, searchNode)

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
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(
            targetStorageCondition = StorageCondition.Freezer))

    val fields = listOf(targetStorageConditionField)

    val result = searchService.search(fields, criteria = NoConditionNode())

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
    accessionsDao.insert(
        AccessionsRow(
            createdTime = Instant.now(),
            number = "MISSING",
            facilityId = config.facilityId,
            stateId = AccessionState.Processing))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(
            targetStorageCondition = StorageCondition.Freezer))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("XYZ")!!.copy(
            targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val searchNode = FieldNode(targetStorageConditionField, listOf("Freezer", null))

    val result = searchService.search(fields, searchNode)

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
    accessionsDao.insert(
        AccessionsRow(
            createdTime = Instant.now(),
            number = "MISSING",
            facilityId = config.facilityId,
            stateId = AccessionState.Processing))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "some matching notes"))
    accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(storageNotes = "not it"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(storageNotesField, listOf("matching", null), SearchFilterType.Fuzzy)

    val result = searchService.search(fields, searchNode)

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
  fun `can specify weight units when searching by grams`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(
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

    val expected = SearchResults(listOf(mapOf("accessionNumber" to "ABCDEFG")), cursor = null)

    val result = searchService.search(fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field defaults to grams if no units explicitly specified`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(
            processingMethodId = ProcessingMethod.Weight,
            totalGrams = BigDecimal(1000),
            totalQuantity = BigDecimal(1),
            totalUnitsId = SeedQuantityUnits.Kilograms))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(totalGramsField, listOf("1000"))

    val expected = SearchResults(listOf(mapOf("accessionNumber" to "ABCDEFG")), cursor = null)

    val result = searchService.search(fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field throws exception for unknown units name`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(totalGramsField, listOf("1000 baseballs"))

    assertThrows(IllegalArgumentException::class.java) { searchService.search(fields, searchNode) }
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

    val firstPage =
        searchService.search(fields, criteria = NoConditionNode(), sortOrder = sortOrder, limit = 1)
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
        searchService.search(
            fields,
            criteria = NoConditionNode(),
            sortOrder = sortOrder,
            cursor = firstPage.cursor,
            limit = 1)
    assertEquals(expectedSecondPage, secondPage)
  }

  @Test
  fun `can search on enum in child table`() {
    accessionGerminationTestTypesDao.insert(
        AccessionGerminationTestTypesRow(1000, GerminationTestType.Lab),
        AccessionGerminationTestTypesRow(1000, GerminationTestType.Nursery),
        AccessionGerminationTestTypesRow(1001, GerminationTestType.Lab))

    val fields = listOf(viabilityTestTypeField)
    val sortOrder =
        listOf(SearchSortField(accessionNumberField), SearchSortField(viabilityTestTypeField))

    val expected =
        SearchResults(
            listOf(
                mapOf("accessionNumber" to "ABCDEFG", "viabilityTestType" to "Lab"),
                mapOf("accessionNumber" to "XYZ", "viabilityTestType" to "Lab"),
                mapOf("accessionNumber" to "XYZ", "viabilityTestType" to "Nursery"),
            ),
            cursor = null)

    val actual = searchService.search(fields, criteria = NoConditionNode(), sortOrder = sortOrder)
    assertEquals(expected, actual)
  }

  @Nested
  inner class CompoundSearchTest {
    @BeforeEach
    fun insertTreesCollectedFromExamples() {
      (10..20).forEach { value ->
        accessionsDao.insert(
            AccessionsRow(
                createdTime = Instant.now(),
                number = "$value",
                facilityId = config.facilityId,
                stateId = AccessionState.Processing,
                treesCollectedFrom = value))
      }
    }

    @Test
    fun `simple field condition`() {
      testSearch(exactly(13), listOf(13))
    }

    @Test
    fun `simple OR condition`() {
      testSearch(OrNode(listOf(exactly(13), exactly(15))), listOf(13, 15))
    }

    @Test
    fun `simple AND condition`() {
      testSearch(AndNode(listOf(between(13, 16), between(15, 18))), listOf(15, 16))
    }

    @Test
    fun `simple NOT condition`() {
      testSearch(NotNode(between(1, 18)), listOf(19, 20))
    }

    @Test
    fun `nested AND conditions inside OR condition`() {
      testSearch(
          OrNode(
              listOf(
                  AndNode(listOf(between(10, 11), between(11, 12))),
                  AndNode(listOf(between(13, 14), between(14, 15))))),
          listOf(11, 14))
    }

    @Test
    fun `nested OR conditions inside AND condition`() {
      testSearch(
          AndNode(
              listOf(
                  OrNode(listOf(exactly(11), exactly(13))),
                  OrNode(listOf(exactly(13), exactly(15))))),
          listOf(13))
    }

    @Test
    fun `nested AND inside OR inside AND with NOT`() {
      testSearch(
          AndNode(
              listOf(
                  OrNode(
                      listOf(
                          AndNode(listOf(between(10, 14), between(11, 20))),
                          AndNode(listOf(between(18, 19), between(19, 20))))),
                  between(12, 20),
                  NotNode(exactly(13)))),
          listOf(12, 14, 19))
    }

    private fun exactly(value: Int) = FieldNode(treesCollectedFromField, listOf("$value"))

    private fun between(minimum: Int?, maximum: Int?): FieldNode {
      return FieldNode(
          treesCollectedFromField,
          listOf(minimum?.toString(), maximum?.toString()),
          type = SearchFilterType.Range)
    }

    private fun testSearch(searchNode: SearchNode, expectedValues: List<Int>) {
      val expected =
          SearchResults(expectedValues.map { value -> mapOf("accessionNumber" to "$value") }, null)
      val actual = searchService.search(listOf(accessionNumberField), searchNode)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class DateFieldSearchTest {
    @BeforeEach
    fun insertReceivedDateExamples() {
      listOf(1, 2, 8).forEach { day ->
        accessionsDao.insert(
            AccessionsRow(
                createdTime = Instant.now(),
                number = "JAN$day",
                facilityId = config.facilityId,
                stateId = AccessionState.Processing,
                receivedDate = LocalDate.of(2021, 1, day)))
      }
    }

    @Test
    fun `can search for exact date`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf("2021-01-02"), SearchFilterType.Exact)

      val expected = SearchResults(listOf(mapOf("accessionNumber" to "JAN2")), null)
      val actual = searchService.search(fields, searchNode)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search for missing date`() {
      val fields = listOf(accessionNumberField)
      val searchNode =
          FieldNode(receivedDateField, listOf("2021-01-02", null), SearchFilterType.Exact)

      val expected =
          SearchResults(
              listOf(
                  mapOf("accessionNumber" to "ABCDEFG"),
                  mapOf("accessionNumber" to "JAN2"),
                  mapOf("accessionNumber" to "XYZ"),
              ),
              null)
      val actual = searchService.search(fields, searchNode)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search by date range`() {
      val fields = listOf(accessionNumberField)
      val sortOrder = listOf(SearchSortField(receivedDateField))
      val searchNode =
          FieldNode(receivedDateField, listOf("2021-01-02", "2021-01-15"), SearchFilterType.Range)

      val expected =
          SearchResults(
              listOf(mapOf("accessionNumber" to "JAN2"), mapOf("accessionNumber" to "JAN8")), null)
      val actual = searchService.search(fields, searchNode, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search by date range with only minimum`() {
      val fields = listOf(accessionNumberField)
      val sortOrder = listOf(SearchSortField(receivedDateField))
      val searchNode =
          FieldNode(receivedDateField, listOf("2021-01-07", null), SearchFilterType.Range)

      val expected = SearchResults(listOf(mapOf("accessionNumber" to "JAN8")), null)
      val actual = searchService.search(fields, searchNode, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search by date range with only maximum`() {
      val fields = listOf(accessionNumberField)
      val sortOrder = listOf(SearchSortField(receivedDateField))
      val searchNode =
          FieldNode(receivedDateField, listOf(null, "2021-01-03"), SearchFilterType.Range)

      val expected =
          SearchResults(
              listOf(mapOf("accessionNumber" to "JAN1"), mapOf("accessionNumber" to "JAN2")), null)
      val actual = searchService.search(fields, searchNode, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `date range with two nulls is rejected`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf(null, null), SearchFilterType.Range)

      assertThrows(IllegalArgumentException::class.java) {
        searchService.search(fields, searchNode)
      }
    }

    @Test
    fun `malformed dates are rejected`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf("NOT_A_DATE"), SearchFilterType.Exact)

      assertThrows(IllegalArgumentException::class.java) {
        searchService.search(fields, searchNode)
      }
    }
  }

  @Test
  fun `fetchValues with no criteria for simple column value`() {
    val values = searchService.fetchValues(speciesField, NoConditionNode())
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fetchValues renders null values as null, not as a string`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(speciesId = null))
    val values = searchService.fetchValues(speciesField, NoConditionNode())
    assertEquals(listOf("Other Dogwood", null), values)
  }

  @Test
  fun `fetchValues with fuzzy search of accession number`() {
    val values =
        searchService.fetchValues(
            speciesField, FieldNode(accessionNumberField, listOf("xyzz"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Kousa Dogwood"), values)
  }

  @Test
  fun `fetchValues with prefix search of accession number`() {
    val values =
        searchService.fetchValues(
            speciesField, FieldNode(accessionNumberField, listOf("a"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Other Dogwood"), values)
  }

  @Test
  fun `fetchValues with fuzzy search of text field in secondary table`() {
    val values =
        searchService.fetchValues(
            speciesField, FieldNode(speciesField, listOf("dogwod"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fetchValues with fuzzy search of text field in secondary table not in field list`() {
    val values =
        searchService.fetchValues(
            stateField, FieldNode(speciesField, listOf("dogwod"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Processed", "Processing"), values)
  }

  @Test
  fun `fetchValues with exact search of integer column value`() {
    val values =
        searchService.fetchValues(
            treesCollectedFromField, FieldNode(treesCollectedFromField, listOf("1")))

    assertEquals(listOf("1"), values)
  }

  @Test
  fun `fetchValues with no criteria for computed column value`() {
    val values = searchService.fetchValues(activeField, NoConditionNode())
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
    val storageLocationDao = StorageLocationsDao(dslContext.configuration())
    storageLocationDao.insert(
        StorageLocationsRow(
            id = 1000,
            facilityId = 100,
            name = "Refrigerator 1",
            conditionId = StorageCondition.Refrigerator))
    storageLocationDao.insert(
        StorageLocationsRow(
            id = 1001,
            facilityId = 100,
            name = "Freezer 1",
            conditionId = StorageCondition.Freezer))
    storageLocationDao.insert(
        StorageLocationsRow(
            id = 1002,
            facilityId = 100,
            name = "Freezer 2",
            conditionId = StorageCondition.Freezer))

    val expected = listOf(null, "Freezer 1", "Freezer 2", "Refrigerator 1")
    val values = searchService.fetchAllValues(storageLocationField)
    assertEquals(expected, values)
  }
}
