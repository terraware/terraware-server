package com.terraformation.backend.seedbank.search

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.GerminationTestId
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.PostgresFuzzySearchOperators
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.tables.daos.AccessionGerminationTestTypesDao
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.BagsDao
import com.terraformation.backend.db.tables.daos.GerminationTestsDao
import com.terraformation.backend.db.tables.daos.GerminationsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.pojos.AccessionGerminationTestTypesRow
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.BagsRow
import com.terraformation.backend.db.tables.pojos.GerminationTestsRow
import com.terraformation.backend.db.tables.pojos.GerminationsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.NotNode
import com.terraformation.backend.search.OrNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SearchServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockk<UserModel>()
  override val sequencesToReset: List<String> = listOf("accession_id_seq")

  private lateinit var accessionsDao: AccessionsDao
  private lateinit var accessionGerminationTestTypesDao: AccessionGerminationTestTypesDao
  private lateinit var bagsDao: BagsDao
  private lateinit var germinationTestsDao: GerminationTestsDao
  private lateinit var germinationsDao: GerminationsDao
  private lateinit var searchService: SearchService

  private val facilityId = FacilityId(100)
  private val checkedInTimeString = "2021-08-18T11:33:55Z"
  private val checkedInTime = Instant.parse(checkedInTimeString)

  private val searchFields = SearchFields(PostgresFuzzySearchOperators())
  private val accessionNumberField = searchFields["accessionNumber"]!!
  private val activeField = searchFields["active"]!!
  private val bagNumberField = searchFields["bagNumber"]!!
  private val checkedInTimeField = searchFields["checkedInTime"]!!
  private val germinationSeedsGerminatedField = searchFields["germinationSeedsGerminated"]!!
  private val germinationSeedsSownField = searchFields["germinationSeedsSown"]!!
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
    val jooqConfig = dslContext.configuration()

    accessionsDao = AccessionsDao(jooqConfig)
    accessionGerminationTestTypesDao = AccessionGerminationTestTypesDao(jooqConfig)
    bagsDao = BagsDao(jooqConfig)
    germinationTestsDao = GerminationTestsDao(jooqConfig)
    germinationsDao = GerminationsDao(jooqConfig)
    searchService = SearchService(dslContext, searchFields)

    every { user.facilityRoles } returns mapOf(facilityId to Role.MANAGER)

    insertSiteData()

    val now = Instant.now()

    val speciesDao = SpeciesDao(jooqConfig)
    speciesDao.insert(
        SpeciesRow(
            id = SpeciesId(10000),
            name = "Kousa Dogwood",
            isScientific = false,
            createdTime = now,
            modifiedTime = now))
    speciesDao.insert(
        SpeciesRow(
            id = SpeciesId(10001),
            name = "Other Dogwood",
            isScientific = false,
            createdTime = now,
            modifiedTime = now))

    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(1000),
            number = "XYZ",
            stateId = AccessionState.Processed,
            checkedInTime = checkedInTime,
            createdTime = now,
            facilityId = facilityId,
            speciesId = SpeciesId(10000),
            treesCollectedFrom = 1))
    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(1001),
            number = "ABCDEFG",
            stateId = AccessionState.Processing,
            createdTime = now,
            facilityId = facilityId,
            speciesId = SpeciesId(10001),
            treesCollectedFrom = 2))
  }

  @Test
  fun `finds example rows`() {
    val fields =
        listOf(
            speciesField,
            accessionNumberField,
            treesCollectedFromField,
            activeField,
            checkedInTimeField)
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "species" to "Kousa Dogwood",
                    "id" to "1000",
                    "accessionNumber" to "XYZ",
                    "treesCollectedFrom" to "1",
                    "active" to "Active",
                    "checkedInTime" to checkedInTimeString,
                ),
                mapOf(
                    "species" to "Other Dogwood",
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "active" to "Active",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `returns full set of values from child field when filtering on that field`() {
    val fields = listOf(bagNumberField)
    val sortOrder = fields.map { SearchSortField(it) }

    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "B"))

    val result =
        searchService.search(
            facilityId,
            fields,
            criteria = FieldNode(bagNumberField, listOf("A")),
            sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1000",
                    "bagNumber" to "A",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "1000",
                    "bagNumber" to "B",
                    "accessionNumber" to "XYZ",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `honors sort order`() {
    val fields = listOf(speciesField, accessionNumberField, treesCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it, SearchDirection.Descending) }

    val result =
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "species" to "Other Dogwood",
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "active" to "Active",
                ),
                mapOf(
                    "species" to "Kousa Dogwood",
                    "id" to "1000",
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

    val result = searchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001", "accessionNumber" to "ABCDEFG", "treesCollectedFrom" to "500")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no minimum`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf(null, "3"), SearchFilterType.Range)

    val result = searchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("id" to "1000", "accessionNumber" to "XYZ", "treesCollectedFrom" to "1")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no maximum`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf("2", null), SearchFilterType.Range)

    val result = searchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001", "accessionNumber" to "ABCDEFG", "treesCollectedFrom" to "500")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `range search on integer field with two nulls is rejected`() {
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf(null, null), SearchFilterType.Range)

    assertThrows(IllegalArgumentException::class.java) {
      searchService.search(facilityId, fields, searchNode)
    }
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

    val result =
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

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

    val result = searchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG", "state" to "In Storage"),
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

    val result = searchService.search(facilityId, fields, criteria = NoConditionNode())

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "targetStorageCondition" to "Freezer"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ"),
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
            facilityId = facilityId,
            stateId = AccessionState.Processing))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(
            targetStorageCondition = StorageCondition.Freezer))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("XYZ")!!.copy(
            targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val searchNode = FieldNode(targetStorageConditionField, listOf("Freezer", null))

    val result = searchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "targetStorageCondition" to "Freezer"),
                mapOf("id" to "1", "accessionNumber" to "MISSING"),
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
            facilityId = facilityId,
            stateId = AccessionState.Processing))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "some matching notes"))
    accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(storageNotes = "not it"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(storageNotesField, listOf("matching", null), SearchFilterType.Fuzzy)

    val result = searchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
                mapOf("id" to "1", "accessionNumber" to "MISSING"),
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

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    val result = searchService.search(facilityId, fields, searchNode)

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

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    val result = searchService.search(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field throws exception for unknown units name`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(totalGramsField, listOf("1000 baseballs"))

    assertThrows(IllegalArgumentException::class.java) {
      searchService.search(facilityId, fields, searchNode)
    }
  }

  @Test
  fun `can search for timestamps using different but equivalent ISO-8601 time format`() {
    val fields = listOf(checkedInTimeField)
    val searchNode =
        FieldNode(
            checkedInTimeField,
            listOf(checkedInTimeString.replace("Z", ".000+00:00")),
            SearchFilterType.Exact)

    val result = searchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1000",
                    "accessionNumber" to "XYZ",
                    "checkedInTime" to checkedInTimeString)),
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
                "id" to "1000",
                "accessionNumber" to "XYZ",
                "treesCollectedFrom" to "1",
                "active" to "Active",
            ),
        )

    val firstPage =
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder, limit = 1)
    assertEquals(expectedFirstPageResults, firstPage.results)
    // We just care that we get a cursor, not what it is specifically
    assertNotNull(firstPage.cursor)

    val expectedSecondPage =
        SearchResults(
            listOf(
                mapOf(
                    "species" to "Other Dogwood",
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "active" to "Active",
                ),
            ),
            cursor = null)

    val secondPage =
        searchService.search(
            facilityId,
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
        AccessionGerminationTestTypesRow(AccessionId(1000), GerminationTestType.Lab),
        AccessionGerminationTestTypesRow(AccessionId(1000), GerminationTestType.Nursery),
        AccessionGerminationTestTypesRow(AccessionId(1001), GerminationTestType.Lab))

    val fields = listOf(viabilityTestTypeField)
    val sortOrder =
        listOf(SearchSortField(accessionNumberField), SearchSortField(viabilityTestTypeField))

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG", "viabilityTestType" to "Lab"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ", "viabilityTestType" to "Lab"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ", "viabilityTestType" to "Nursery"),
            ),
            cursor = null)

    val actual =
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)
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
                facilityId = facilityId,
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
          SearchResults(
              expectedValues.map { value ->
                // We create accession numbers 10 through 20 and the ID sequence starts at 1
                val id = value - 9
                mapOf("id" to "$id", "accessionNumber" to "$value")
              },
              null)
      val actual = searchService.search(facilityId, listOf(accessionNumberField), searchNode)

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
                facilityId = facilityId,
                stateId = AccessionState.Processing,
                receivedDate = LocalDate.of(2021, 1, day)))
      }
    }

    @Test
    fun `can search for exact date`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf("2021-01-02"), SearchFilterType.Exact)

      val expected = SearchResults(listOf(mapOf("id" to "2", "accessionNumber" to "JAN2")), null)
      val actual = searchService.search(facilityId, fields, searchNode)

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
                  mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
                  mapOf("id" to "2", "accessionNumber" to "JAN2"),
                  mapOf("id" to "1000", "accessionNumber" to "XYZ"),
              ),
              null)
      val actual = searchService.search(facilityId, fields, searchNode)

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
              listOf(
                  mapOf("id" to "2", "accessionNumber" to "JAN2"),
                  mapOf("id" to "3", "accessionNumber" to "JAN8")),
              null)
      val actual = searchService.search(facilityId, fields, searchNode, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search by date range with only minimum`() {
      val fields = listOf(accessionNumberField)
      val sortOrder = listOf(SearchSortField(receivedDateField))
      val searchNode =
          FieldNode(receivedDateField, listOf("2021-01-07", null), SearchFilterType.Range)

      val expected = SearchResults(listOf(mapOf("id" to "3", "accessionNumber" to "JAN8")), null)
      val actual = searchService.search(facilityId, fields, searchNode, sortOrder)

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
              listOf(
                  mapOf("id" to "1", "accessionNumber" to "JAN1"),
                  mapOf("id" to "2", "accessionNumber" to "JAN2")),
              null)
      val actual = searchService.search(facilityId, fields, searchNode, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `date range with two nulls is rejected`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf(null, null), SearchFilterType.Range)

      assertThrows(IllegalArgumentException::class.java) {
        searchService.search(facilityId, fields, searchNode)
      }
    }

    @Test
    fun `malformed dates are rejected`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf("NOT_A_DATE"), SearchFilterType.Exact)

      assertThrows(IllegalArgumentException::class.java) {
        searchService.search(facilityId, fields, searchNode)
      }
    }
  }

  @Nested
  inner class FetchValuesTest {
    @Test
    fun `no criteria for simple column value`() {
      val values = searchService.fetchValues(speciesField, NoConditionNode())
      assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
    }

    @Test
    fun `renders null values as null, not as a string`() {
      accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(speciesId = null))
      val values = searchService.fetchValues(speciesField, NoConditionNode())
      assertEquals(listOf("Other Dogwood", null), values)
    }

    @Test
    fun `fuzzy search of accession number`() {
      val values =
          searchService.fetchValues(
              speciesField, FieldNode(accessionNumberField, listOf("xyzz"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Kousa Dogwood"), values)
    }

    @Test
    fun `prefix search of accession number`() {
      val values =
          searchService.fetchValues(
              speciesField, FieldNode(accessionNumberField, listOf("a"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Other Dogwood"), values)
    }

    @Test
    fun `fuzzy search of text field in secondary table`() {
      val values =
          searchService.fetchValues(
              speciesField, FieldNode(speciesField, listOf("dogwod"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
    }

    @Test
    fun `fuzzy search of text field in secondary table not in field list`() {
      val values =
          searchService.fetchValues(
              stateField, FieldNode(speciesField, listOf("dogwod"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Processed", "Processing"), values)
    }

    @Test
    fun `exact search of integer column value`() {
      val values =
          searchService.fetchValues(
              treesCollectedFromField, FieldNode(treesCollectedFromField, listOf("1")))

      assertEquals(listOf("1"), values)
    }

    @Test
    fun `no criteria for computed column value`() {
      val values = searchService.fetchValues(activeField, NoConditionNode())
      assertEquals(listOf("Active"), values)
    }

    @Test
    fun `only includes values from accessions the user has permission to view`() {
      insertProject(11)
      insertSite(110)
      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              treesCollectedFrom = 3))

      val expected = listOf("1", "2")

      val actual = searchService.fetchValues(treesCollectedFromField, NoConditionNode())

      assertEquals(expected, actual)
    }

    @Test
    fun `includes values from accessions at multiple facilities`() {
      every { user.facilityRoles } returns
          mapOf(facilityId to Role.MANAGER, FacilityId(1100) to Role.CONTRIBUTOR)

      insertProject(11)
      insertSite(110)
      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              treesCollectedFrom = 3))

      val expected = listOf("1", "2", "3")

      val actual = searchService.fetchValues(treesCollectedFromField, NoConditionNode())

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchAllValuesTest {
    @Test
    fun `does not return null for non-nullable field`() {
      val values = searchService.fetchAllValues(accessionNumberField)
      assertFalse(values.any { it == null }, "List of values should not contain null")
    }

    @Test
    fun `returns values for enum-mapped field`() {
      val expected = listOf(null) + GerminationTestType.values().map { it.displayName }
      val values = searchService.fetchAllValues(germinationTestTypeField)
      assertEquals(expected, values)
    }

    @Test
    fun `returns values for free-text field on accession table`() {
      val expected = listOf(null, "Kousa Dogwood", "Other Dogwood")
      val values = searchService.fetchAllValues(speciesField)
      assertEquals(expected, values)
    }

    @Test
    fun `returns values for field from reference table`() {
      val storageLocationDao = StorageLocationsDao(dslContext.configuration())
      storageLocationDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1000),
              facilityId = FacilityId(100),
              name = "Refrigerator 1",
              conditionId = StorageCondition.Refrigerator))
      storageLocationDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1001),
              facilityId = FacilityId(100),
              name = "Freezer 1",
              conditionId = StorageCondition.Freezer))
      storageLocationDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1002),
              facilityId = FacilityId(100),
              name = "Freezer 2",
              conditionId = StorageCondition.Freezer))

      val expected = listOf(null, "Freezer 1", "Freezer 2", "Refrigerator 1")
      val values = searchService.fetchAllValues(storageLocationField)
      assertEquals(expected, values)
    }

    @Test
    fun `only includes storage locations the user has permission to view`() {
      val storageLocationDao = StorageLocationsDao(dslContext.configuration())
      insertProject(11)
      insertSite(110)
      insertFacility(1100)

      storageLocationDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1000),
              facilityId = FacilityId(100),
              name = "Facility 100 fridge",
              conditionId = StorageCondition.Refrigerator))
      storageLocationDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1001),
              facilityId = FacilityId(1100),
              name = "Facility 1100 fridge",
              conditionId = StorageCondition.Refrigerator))

      val expected = listOf(null, "Facility 100 fridge")

      val actual = searchService.fetchAllValues(storageLocationField)

      assertEquals(expected, actual)
    }

    @Test
    fun `only includes accession values the user has permission to view`() {
      insertProject(11)
      insertSite(110)
      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              treesCollectedFrom = 3))

      val expected = listOf(null, "1", "2")

      val actual = searchService.fetchAllValues(treesCollectedFromField)

      assertEquals(expected, actual)
    }

    @Test
    fun `only includes child table values the user has permission to view`() {
      val germinationTestsDao = GerminationTestsDao(dslContext.configuration())
      val germinationsDao = GerminationsDao(dslContext.configuration())

      val hiddenAccessionId = AccessionId(1100)

      insertProject(11)
      insertSite(110)
      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = hiddenAccessionId,
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              treesCollectedFrom = 3))

      listOf(1000, 1100).forEach { id ->
        val accessionId = AccessionId(id.toLong())
        val testId = GerminationTestId(id.toLong())

        accessionGerminationTestTypesDao.insert(
            AccessionGerminationTestTypesRow(accessionId, GerminationTestType.Lab))
        germinationTestsDao.insert(
            GerminationTestsRow(
                id = testId,
                accessionId = accessionId,
                testType = GerminationTestType.Lab,
                remainingQuantity = BigDecimal.ONE,
                remainingUnitsId = SeedQuantityUnits.Grams,
                seedsSown = id))
        germinationsDao.insert(
            GerminationsRow(testId = testId, recordingDate = LocalDate.EPOCH, seedsGerminated = id))
      }

      assertEquals(
          listOf(null, "1000"),
          searchService.fetchAllValues(germinationSeedsGerminatedField),
          "Value from germinations table (grandchild of accessions)")

      assertEquals(
          listOf(null, "1000"),
          searchService.fetchAllValues(germinationSeedsSownField),
          "Value from germination_tests table (child of accessions)")
    }
  }

  @Test
  fun `search only includes accessions at facilities the user has permission to view`() {
    // A facility in an org the user isn't in
    insertOrganization(2)
    insertProject(20)
    insertSite(200)
    insertFacility(2000)

    // A facility in a project the user isn't in (but in an org they're in)
    insertProject(11)
    insertSite(110)
    insertFacility(1100)

    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(2000),
            number = "OtherOrg",
            stateId = AccessionState.Processed,
            createdTime = Instant.EPOCH,
            facilityId = FacilityId(2000)))

    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(1100),
            number = "OtherProject",
            stateId = AccessionState.Processed,
            createdTime = Instant.EPOCH,
            facilityId = FacilityId(1100)))

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
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `search returns empty result if user has no permission to view anything`() {
    every { user.facilityRoles } returns emptyMap()

    val fields = listOf(accessionNumberField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expected = SearchResults(emptyList(), cursor = null)

    val actual =
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `search only includes results from requested facility`() {
    every { user.facilityRoles } returns
        mapOf(facilityId to Role.MANAGER, FacilityId(1100) to Role.CONTRIBUTOR)

    insertProject(11)
    insertSite(110)
    insertFacility(1100)

    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(1100),
            number = "OtherProject",
            stateId = AccessionState.Processed,
            createdTime = Instant.EPOCH,
            facilityId = FacilityId(1100)))

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
        searchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }

  @Nested
  inner class NestedFieldsTest {
    private val bagsNumberField = searchFields["bags.number"]!!
    private val seedsSownField = searchFields["germinationTests.seedsSown"]!!
    private val seedsGerminatedField =
        searchFields["germinationTests.germinations.seedsGerminated"]!!
    private val testTypeField = searchFields["germinationTests.type"]!!

    private lateinit var testId: GerminationTestId
    @BeforeEach
    fun insertNestedData() {
      bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "1"))
      bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "5"))
      bagsDao.insert(BagsRow(accessionId = AccessionId(1001), bagNumber = "2"))
      bagsDao.insert(BagsRow(accessionId = AccessionId(1001), bagNumber = "6"))

      val germinationTestsRow =
          GerminationTestsRow(
              accessionId = AccessionId(1000),
              testType = GerminationTestType.Lab,
              seedsSown = 15,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Grams)

      germinationTestsDao.insert(germinationTestsRow)

      testId = germinationTestsRow.id!!

      germinationsDao.insert(
          GerminationsRow(testId = testId, recordingDate = LocalDate.EPOCH, seedsGerminated = 5))
      germinationsDao.insert(
          GerminationsRow(
              testId = testId, recordingDate = LocalDate.EPOCH.plusDays(1), seedsGerminated = 10))
    }

    @Test
    fun `can filter on nested field`() {
      val fields = listOf(bagsNumberField)
      val sortOrder = fields.map { SearchSortField(it) }

      val result =
          searchService.search(
              facilityId,
              fields,
              criteria = FieldNode(bagsNumberField, listOf("1")),
              sortOrder = sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                      "accessionNumber" to "XYZ",
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can sort ascending on nested field`() {
      val fields = listOf(bagsNumberField)

      val result =
          searchService.search(
              facilityId,
              fields,
              criteria = NoConditionNode(),
              sortOrder = listOf(SearchSortField(bagsNumberField)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                      "accessionNumber" to "XYZ",
                  ),
                  mapOf(
                      "id" to "1001",
                      "bags" to listOf(mapOf("number" to "2"), mapOf("number" to "6")),
                      "accessionNumber" to "ABCDEFG",
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can sort on grandchild field`() {
      val fields = listOf(seedsGerminatedField)

      val result =
          searchService.search(
              facilityId,
              fields,
              FieldNode(seedsGerminatedField, listOf("1", "100"), SearchFilterType.Range),
              listOf(SearchSortField(seedsGerminatedField, SearchDirection.Descending)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "germinationTests" to
                          listOf(
                              mapOf(
                                  "germinations" to
                                      listOf(
                                          mapOf("seedsGerminated" to "10"),
                                          mapOf("seedsGerminated" to "5")))))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can specify duplicate sort fields`() {
      val fields = listOf(seedsGerminatedField)

      val result =
          searchService.search(
              facilityId,
              fields,
              FieldNode(seedsGerminatedField, listOf("1", "100"), SearchFilterType.Range),
              listOf(
                  SearchSortField(seedsGerminatedField, SearchDirection.Descending),
                  SearchSortField(seedsGerminatedField, SearchDirection.Ascending)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "germinationTests" to
                          listOf(
                              mapOf(
                                  "germinations" to
                                      listOf(
                                          mapOf("seedsGerminated" to "10"),
                                          mapOf("seedsGerminated" to "5")))))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can sort descending on nested field`() {
      val fields = listOf(bagsNumberField)

      val result =
          searchService.search(
              facilityId,
              fields,
              criteria = NoConditionNode(),
              sortOrder = listOf(SearchSortField(bagsNumberField, SearchDirection.Descending)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1001",
                      "bags" to listOf(mapOf("number" to "6"), mapOf("number" to "2")),
                      "accessionNumber" to "ABCDEFG",
                  ),
                  mapOf(
                      "id" to "1000",
                      "bags" to listOf(mapOf("number" to "5"), mapOf("number" to "1")),
                      "accessionNumber" to "XYZ",
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can sort on nested enum field`() {
      val fields = listOf(seedsSownField, testTypeField)

      germinationTestsDao.insert(
          GerminationTestsRow(
              accessionId = AccessionId(1000),
              testType = GerminationTestType.Nursery,
              seedsSown = 1,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Grams))

      val result =
          searchService.search(
              facilityId,
              fields,
              criteria = NoConditionNode(),
              sortOrder = listOf(SearchSortField(testTypeField)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "germinationTests" to
                          listOf(
                              mapOf("seedsSown" to "15", "type" to "Lab"),
                              mapOf("seedsSown" to "1", "type" to "Nursery"),
                          ),
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
    fun `returns nested results for multiple fields`() {
      val fields = listOf(bagsNumberField, seedsGerminatedField, seedsSownField)

      val result = searchService.search(facilityId, fields, criteria = NoConditionNode())

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1001",
                      "accessionNumber" to "ABCDEFG",
                      "bags" to listOf(mapOf("number" to "2"), mapOf("number" to "6"))),
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                      "germinationTests" to
                          listOf(
                              mapOf(
                                  "seedsSown" to "15",
                                  "germinations" to
                                      listOf(
                                          mapOf("seedsGerminated" to "5"),
                                          mapOf("seedsGerminated" to "10"),
                                      ),
                              ),
                          ),
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `returns nested results for grandchild field when child field not queried`() {
      val fields = listOf(seedsGerminatedField)
      val sortOrder = fields.map { SearchSortField(it) }

      val result =
          searchService.search(
              facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "germinationTests" to
                          listOf(
                              mapOf(
                                  "germinations" to
                                      listOf(
                                          mapOf("seedsGerminated" to "5"),
                                          mapOf("seedsGerminated" to "10")))),
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
    fun `can sort on nested field that is not in list of query fields`() {
      val sortOrder = listOf(SearchSortField(bagsNumberField, SearchDirection.Descending))

      val result = searchService.search(facilityId, emptyList(), NoConditionNode(), sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
                  mapOf("id" to "1000", "accessionNumber" to "XYZ"),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `returns nested result for each row when querying non-nested child field`() {
      val fields = listOf(germinationSeedsGerminatedField, seedsGerminatedField)
      val sortOrder = fields.map { SearchSortField(it) }
      val criteria = FieldNode(seedsGerminatedField, listOf("1", "100"), SearchFilterType.Range)

      val result = searchService.search(facilityId, fields, criteria, sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "germinationTests" to
                          listOf(
                              mapOf(
                                  "germinations" to
                                      listOf(
                                          mapOf("seedsGerminated" to "5"),
                                          mapOf("seedsGerminated" to "10")))),
                      "germinationSeedsGerminated" to "5",
                  ),
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "germinationTests" to
                          listOf(
                              mapOf(
                                  "germinations" to
                                      listOf(
                                          mapOf("seedsGerminated" to "5"),
                                          mapOf("seedsGerminated" to "10")))),
                      "germinationSeedsGerminated" to "10",
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can search and sort all the fields`() {
      val fields = searchFields.fieldNames.sorted().map { searchFields[it]!! }
      val sortOrder = fields.map { SearchSortField(it) }

      // We're querying a mix of nested fields and the old-style fields that put nested values
      // at the top level and return a separate top-level query result for each combination of rows
      // in each child table.
      //
      // The end result is a large Cartesian product which would be hundreds of lines long if it
      // were represented explicitly as a series of nested lists and maps in Kotlin code, or if it
      // were loaded from a JSON file.
      //
      // So instead, we compute the product by iterating over the nested fields in the same order
      // as the non-nested fields appear in the `sortOrder` list, copying values from the nested
      // fields to their non-nested equivalents.

      fun Map<String, Any>.getListValue(name: String): List<Map<String, Any>>? {
        @Suppress("UNCHECKED_CAST") return get(name) as List<Map<String, Any>>?
      }

      fun MutableMap<String, Any>.putIfNotNull(key: String, value: Any?) {
        if (value != null) {
          put(key, value)
        }
      }

      val expected =
          listOf(
              mapOf(
                  "id" to "1001",
                  "accessionNumber" to "ABCDEFG",
                  "active" to "Active",
                  "bags" to listOf(mapOf("number" to "2"), mapOf("number" to "6")),
                  "species" to "Other Dogwood",
                  "state" to "Processing",
                  "treesCollectedFrom" to "2",
              ),
              mapOf(
                  "id" to "1000",
                  "accessionNumber" to "XYZ",
                  "active" to "Active",
                  "checkedInTime" to "$checkedInTime",
                  "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                  "germinationTests" to
                      listOf(
                          mapOf(
                              "germinations" to
                                  listOf(
                                      mapOf(
                                          "recordingDate" to "1970-01-01",
                                          "seedsGerminated" to "5"),
                                      mapOf(
                                          "recordingDate" to "1970-01-02",
                                          "seedsGerminated" to "10")),
                              "type" to "Lab",
                              "seedsSown" to "15",
                          )),
                  "species" to "Kousa Dogwood",
                  "state" to "Processed",
                  "treesCollectedFrom" to "1",
              ))
              .flatMap { base ->
                base.getListValue("bags")?.flatMap { bag ->
                  val perBagNumber = base.toMutableMap()
                  perBagNumber.putIfNotNull("bagNumber", bag["number"])

                  base.getListValue("germinationTests")?.flatMap { germinationTest ->
                    val perTest = perBagNumber.toMutableMap()
                    perTest.putIfNotNull("germinationSeedsSown", germinationTest["seedsSown"])
                    perTest.putIfNotNull("germinationTestType", germinationTest["type"])

                    germinationTest.getListValue("germinations")?.map { germination ->
                      val perGermination = perTest.toMutableMap()
                      perGermination.putIfNotNull(
                          "germinationSeedsGerminated", germination["seedsGerminated"])
                      perGermination
                    }
                        ?: listOf(perTest)
                  }
                      ?: listOf(perBagNumber)
                }
                    ?: listOf(base)
              }

      val result = searchService.search(facilityId, fields, NoConditionNode(), sortOrder)

      // Compare sorted versions of the result maps to make differences easier to spot.
      assertEquals(expected.map { it.toSortedMap() }, result.results.map { it.toSortedMap() })
      assertNull(result.cursor)
    }
  }
}
