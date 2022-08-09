package com.terraformation.backend.seedbank.search

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.SeedStorageBehavior
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.ViabilityTestType
import com.terraformation.backend.db.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.BagsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
import com.terraformation.backend.db.tables.pojos.ViabilityTestResultsRow
import com.terraformation.backend.db.tables.pojos.ViabilityTestSelectionsRow
import com.terraformation.backend.db.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.NotNode
import com.terraformation.backend.search.OrNode
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchFieldPath
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.field.AliasField
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class SearchServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ACCESSIONS)

  private lateinit var accessionSearchService: AccessionSearchService
  private lateinit var searchService: SearchService

  private val checkedInTimeString = "2021-08-18T11:33:55Z"
  private val checkedInTime = Instant.parse(checkedInTimeString)
  private val searchScopes = listOf(OrganizationIdScope(organizationId))

  private val clock: Clock = mockk()

  private val tables = SearchTables(clock)
  private val accessionsTable = tables.accessions
  private val rootPrefix = SearchFieldPrefix(root = accessionsTable)
  private val accessionNumberField = rootPrefix.resolve("accessionNumber")
  private val activeField = rootPrefix.resolve("active")
  private val bagNumberField = rootPrefix.resolve("bagNumber")
  private val bagNumberFlattenedField = rootPrefix.resolve("bags_number")
  private val checkedInTimeField = rootPrefix.resolve("checkedInTime")
  private val receivedDateField = rootPrefix.resolve("receivedDate")
  private val speciesNameField = rootPrefix.resolve("speciesName")
  private val stateField = rootPrefix.resolve("state")
  private val storageLocationNameField = rootPrefix.resolve("storageLocationName")
  private val storageNotesField = rootPrefix.resolve("storageNotes")
  private val targetStorageConditionField = rootPrefix.resolve("targetStorageCondition")
  private val totalGramsField = rootPrefix.resolve("totalGrams")
  private val treesCollectedFromField = rootPrefix.resolve("treesCollectedFrom")
  private val treesCollectedFromAlias =
      SearchFieldPath(rootPrefix, AliasField("treesCollectedFromAlias", treesCollectedFromField))
  private val viabilityTestResultsSeedsGerminatedField =
      rootPrefix.resolve("viabilityTests_viabilityTestResults_seedsGerminated")
  private val viabilityTestSeedsSownField = rootPrefix.resolve("viabilityTests_seedsSown")
  private val viabilityTestsTypeField = rootPrefix.resolve("viabilityTests_type")

  @BeforeEach
  fun init() {
    searchService = SearchService(dslContext)
    accessionSearchService = AccessionSearchService(tables, searchService)

    every { clock.instant() } returns Instant.parse("2020-06-15T00:00:00.00Z")
    every { clock.zone } returns ZoneOffset.UTC
    every { user.organizationRoles } returns mapOf(organizationId to Role.MANAGER)
    every { user.facilityRoles } returns mapOf(facilityId to Role.MANAGER)

    insertSiteData()

    insertOrganizationUser(role = Role.MANAGER)

    val now = Instant.now()

    speciesDao.insert(
        SpeciesRow(
            id = SpeciesId(10000),
            scientificName = "Kousa Dogwood",
            initialScientificName = "Kousa Dogwood",
            commonName = "Common 1",
            rare = false,
            growthFormId = GrowthForm.Graminoid,
            createdBy = user.userId,
            createdTime = now,
            modifiedBy = user.userId,
            modifiedTime = now,
            organizationId = organizationId))
    speciesDao.insert(
        SpeciesRow(
            id = SpeciesId(10001),
            scientificName = "Other Dogwood",
            initialScientificName = "Other Dogwood",
            commonName = "Common 2",
            endangered = true,
            seedStorageBehaviorId = SeedStorageBehavior.Orthodox,
            createdBy = user.userId,
            createdTime = now,
            modifiedBy = user.userId,
            modifiedTime = now,
            organizationId = organizationId))
    speciesDao.insert(
        SpeciesRow(
            id = SpeciesId(10002),
            scientificName = "Deleted species",
            initialScientificName = "Deleted species",
            createdBy = user.userId,
            createdTime = now,
            modifiedBy = user.userId,
            modifiedTime = now,
            deletedBy = user.userId,
            deletedTime = now,
            organizationId = organizationId))

    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(1000),
            number = "XYZ",
            stateId = AccessionState.Processed,
            checkedInTime = checkedInTime,
            collectedDate = LocalDate.of(2019, 3, 2),
            createdBy = user.userId,
            createdTime = now,
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = now,
            speciesId = SpeciesId(10000),
            treesCollectedFrom = 1))
    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(1001),
            number = "ABCDEFG",
            stateId = AccessionState.Processing,
            createdBy = user.userId,
            createdTime = now,
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = now,
            speciesId = SpeciesId(10001),
            treesCollectedFrom = 2))

    accessionCollectorsDao.insert(
        AccessionCollectorsRow(AccessionId(1000), 0, "primary"),
        AccessionCollectorsRow(AccessionId(1000), 1, "secondary 1"),
        AccessionCollectorsRow(AccessionId(1000), 2, "secondary 2"),
    )
  }

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

        table.fields.forEach { _ ->
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
            treesCollectedFromField,
            activeField,
            checkedInTimeField)
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "speciesName" to "Kousa Dogwood",
                    "id" to "1000",
                    "accessionNumber" to "XYZ",
                    "treesCollectedFrom" to "1",
                    "active" to "Active",
                    "checkedInTime" to checkedInTimeString,
                ),
                mapOf(
                    "speciesName" to "Other Dogwood",
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
        accessionSearchService.search(
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
  fun `returns multiple results for field in flattened sublist`() {
    val fields = listOf(bagNumberFlattenedField)
    val sortOrder = fields.map { SearchSortField(it) }

    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "B"))

    val result =
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1000",
                    "bags_number" to "A",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "1000",
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
  fun `can query both an alias field and its target`() {
    val fields = listOf(bagNumberField, bagNumberFlattenedField)
    val sortOrder = fields.map { SearchSortField(it) }

    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "B"))

    val result =
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

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
  fun `honors sort order`() {
    val fields =
        listOf(speciesNameField, accessionNumberField, treesCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it, SearchDirection.Descending) }

    val result =
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "speciesName" to "Other Dogwood",
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "active" to "Active",
                ),
                mapOf(
                    "speciesName" to "Kousa Dogwood",
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

    val result = accessionSearchService.search(facilityId, fields, searchNode)

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

    val result = accessionSearchService.search(facilityId, fields, searchNode)

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

    val result = accessionSearchService.search(facilityId, fields, searchNode)

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

    assertThrows<IllegalArgumentException> {
      accessionSearchService.search(facilityId, fields, searchNode)
    }
  }

  @Test
  fun `can filter on computed fields whose raw values are being queried`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("XYZ")!!.copy(stateId = AccessionState.Withdrawn))

    val fields = listOf(accessionNumberField, stateField)
    val searchNode = FieldNode(activeField, listOf("Inactive"))

    val result = accessionSearchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("id" to "1000", "accessionNumber" to "XYZ", "state" to "Withdrawn")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `sorts enum fields by display name rather than ID`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("ABCDEFG")!!
            .copy(targetStorageCondition = StorageCondition.Freezer))
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("XYZ")!!
            .copy(targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val sortOrder = listOf(SearchSortField(targetStorageConditionField, SearchDirection.Descending))

    val result =
        accessionSearchService.search(
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

    val result = accessionSearchService.search(facilityId, fields, searchNode)

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
        accessionsDao
            .fetchOneByNumber("ABCDEFG")!!
            .copy(targetStorageCondition = StorageCondition.Freezer))

    val fields = listOf(targetStorageConditionField)

    val result = accessionSearchService.search(facilityId, fields, criteria = NoConditionNode())

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
            createdBy = user.userId,
            createdTime = Instant.now(),
            number = "MISSING",
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
            stateId = AccessionState.Processing))
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("ABCDEFG")!!
            .copy(targetStorageCondition = StorageCondition.Freezer))
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("XYZ")!!
            .copy(targetStorageCondition = StorageCondition.Refrigerator))

    val fields = listOf(targetStorageConditionField)
    val searchNode = FieldNode(targetStorageConditionField, listOf("Freezer", null))

    val result = accessionSearchService.search(facilityId, fields, searchNode)

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
            createdBy = user.userId,
            createdTime = Instant.now(),
            number = "MISSING",
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
            stateId = AccessionState.Processing))
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "some matching notes"))
    accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(storageNotes = "not it"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(storageNotesField, listOf("matching", null), SearchFilterType.Fuzzy)

    val result = accessionSearchService.search(facilityId, fields, searchNode)

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
  fun `fuzzy search on text fields is case-insensitive`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "Some Matching Notes"))
    accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(storageNotes = "Not It"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(storageNotesField, listOf("matc"), SearchFilterType.Fuzzy)

    val result = accessionSearchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `fuzzy search on text fields handles single-character search values`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "Some Matching Notes"))
    accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(storageNotes = "Not It"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(storageNotesField, listOf("G"), SearchFilterType.Fuzzy)

    val result = accessionSearchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `exact search on text fields is case-insensitive`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "Some Matching Notes"))

    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(storageNotesField, listOf("some matching Notes"), SearchFilterType.Exact)

    val result = accessionSearchService.search(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can specify weight units when searching by grams`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("ABCDEFG")!!
            .copy(
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

    val result = accessionSearchService.search(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field defaults to grams if no units explicitly specified`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneByNumber("ABCDEFG")!!
            .copy(
                processingMethodId = ProcessingMethod.Weight,
                totalGrams = BigDecimal(1000),
                totalQuantity = BigDecimal(1),
                totalUnitsId = SeedQuantityUnits.Kilograms))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(totalGramsField, listOf("1000"))

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    val result = accessionSearchService.search(facilityId, fields, searchNode)

    assertEquals(expected, result)
  }

  @Test
  fun `searching on grams field throws exception for unknown units name`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(totalGramsField, listOf("1000 baseballs"))

    assertThrows<IllegalArgumentException> {
      accessionSearchService.search(facilityId, fields, searchNode)
    }
  }

  @Test
  fun `can search for timestamps using different but equivalent RFC 3339 time format`() {
    val fields = listOf(checkedInTimeField)
    val searchNode =
        FieldNode(
            checkedInTimeField,
            listOf(checkedInTimeString.replace("Z", ".000+00:00")),
            SearchFilterType.Exact)

    val result = accessionSearchService.search(facilityId, fields, searchNode)

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
    val fields =
        listOf(speciesNameField, accessionNumberField, treesCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expectedFirstPageResults =
        listOf(
            mapOf(
                "speciesName" to "Kousa Dogwood",
                "id" to "1000",
                "accessionNumber" to "XYZ",
                "treesCollectedFrom" to "1",
                "active" to "Active",
            ),
        )

    val firstPage =
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder, limit = 1)
    assertEquals(expectedFirstPageResults, firstPage.results)
    // We just care that we get a cursor, not what it is specifically
    assertNotNull(firstPage.cursor)

    val expectedSecondPage =
        SearchResults(
            listOf(
                mapOf(
                    "speciesName" to "Other Dogwood",
                    "id" to "1001",
                    "accessionNumber" to "ABCDEFG",
                    "treesCollectedFrom" to "2",
                    "active" to "Active",
                ),
            ),
            cursor = null)

    val secondPage =
        accessionSearchService.search(
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
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            accessionId = AccessionId(1000),
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Seeds,
            testType = ViabilityTestType.Lab,
        ),
        ViabilityTestsRow(
            accessionId = AccessionId(1000),
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Seeds,
            testType = ViabilityTestType.Nursery,
        ),
        ViabilityTestsRow(
            accessionId = AccessionId(1001),
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Seeds,
            testType = ViabilityTestType.Lab,
        ),
    )

    val fields = listOf(viabilityTestsTypeField)
    val sortOrder =
        listOf(SearchSortField(accessionNumberField), SearchSortField(viabilityTestsTypeField))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001", "accessionNumber" to "ABCDEFG", "viabilityTests_type" to "Lab"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ", "viabilityTests_type" to "Lab"),
                mapOf(
                    "id" to "1000", "accessionNumber" to "XYZ", "viabilityTests_type" to "Nursery"),
            ),
            cursor = null)

    val actual =
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)
    assertEquals(expected, actual)
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
        accessionSearchService.search(
            facilityId, listOf(treesCollectedFromAlias), criteria = NoConditionNode())
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

    val actual = accessionSearchService.search(facilityId, emptyList(), criteria = criteria)
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
        accessionSearchService.search(
            facilityId, emptyList(), criteria = NoConditionNode(), sortOrder = sortOrder)
    assertEquals(expected, actual)
  }

  @Nested
  inner class CompoundSearchTest {
    @BeforeEach
    fun insertTreesCollectedFromExamples() {
      (10..20).forEach { value ->
        accessionsDao.insert(
            AccessionsRow(
                createdBy = user.userId,
                createdTime = Instant.now(),
                number = "$value",
                facilityId = facilityId,
                modifiedBy = user.userId,
                modifiedTime = Instant.now(),
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
      val actual =
          accessionSearchService.search(facilityId, listOf(accessionNumberField), searchNode)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class AgeFieldTest {
    private val ageMonthsField = rootPrefix.resolve("ageMonths")
    private val ageYearsField = rootPrefix.resolve("ageYears")
    private val collectedDateField = rootPrefix.resolve("collectedDate")
    private val idField = rootPrefix.resolve("id")

    @Test
    fun `can search for exact ages`() {
      listOf(1100L, 1101L, 1102L).forEach { id ->
        accessionsDao.insert(
            AccessionsRow(
                id = AccessionId(id),
                number = "$id",
                stateId = AccessionState.Processed,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH))
      }

      setCollectedDates(
          1000 to "2018-01-01", // 29 months old
          1001 to "2019-01-01", // 17 months old
          1100 to "2020-01-01", // 5 months old
          1101 to "2020-06-01", // 0 months old
          1102 to null,
      )

      val searchNode = FieldNode(ageMonthsField, listOf("0", "17", null))
      val sortField = SearchSortField(ageMonthsField)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1101",
                      "ageMonths" to "0",
                      "ageYears" to "0",
                      "collectedDate" to "2020-06-01",
                  ),
                  mapOf(
                      "id" to "1001",
                      "ageMonths" to "17",
                      "ageYears" to "1",
                      "collectedDate" to "2019-01-01",
                  ),
                  mapOf("id" to "1102"),
              ),
              null)
      val actual =
          searchService.search(
              rootPrefix,
              listOf(idField, ageMonthsField, ageYearsField, collectedDateField),
              searchNode,
              listOf(sortField))

      assertEquals(expected, actual)
    }

    @Test
    fun `sorting by age in months uses underlying date values`() {
      setCollectedDates(1000 to "2020-06-01", 1001 to "2020-06-02")

      val searchNode = FieldNode(ageMonthsField, listOf("0"))
      val sortField = SearchSortField(ageMonthsField, SearchDirection.Descending)

      val expected =
          SearchResults(
              listOf(
                  // Oldest first, meaning ascending date order
                  mapOf("id" to "1000", "ageMonths" to "0", "collectedDate" to "2020-06-01"),
                  mapOf("id" to "1001", "ageMonths" to "0", "collectedDate" to "2020-06-02"),
              ),
              null)

      val actual =
          searchService.search(
              rootPrefix,
              listOf(idField, ageMonthsField, collectedDateField),
              searchNode,
              listOf(sortField))

      assertEquals(expected, actual)
    }

    @Test
    fun `sorting by age in years uses underlying date values`() {
      setCollectedDates(1000 to "2020-06-01", 1001 to "2020-06-02")

      val searchNode = FieldNode(ageYearsField, listOf("0"))
      val sortField = SearchSortField(ageYearsField)

      val expected =
          SearchResults(
              listOf(
                  // Youngest first, meaning reverse date order
                  mapOf("id" to "1001", "ageYears" to "0", "collectedDate" to "2020-06-02"),
                  mapOf("id" to "1000", "ageYears" to "0", "collectedDate" to "2020-06-01"),
              ),
              null)

      val actual =
          searchService.search(
              rootPrefix,
              listOf(idField, ageYearsField, collectedDateField),
              searchNode,
              listOf(sortField))

      assertEquals(expected, actual)
    }

    @Test
    fun `searching for null age returns accessions without collected dates`() {
      setCollectedDates(1000 to null, 1001 to "2020-05-31")

      val searchNode = FieldNode(ageMonthsField, listOf(null))

      val expected = SearchResults(listOf(mapOf("id" to "1000")), null)
      val actual = searchService.search(rootPrefix, listOf(idField), searchNode)

      assertEquals(expected, actual)
    }

    @Test
    fun `searching for 0 months returns accessions from current month`() {
      setCollectedDates(1000 to "2020-06-01", 1001 to "2020-05-31")

      val searchNode = FieldNode(ageMonthsField, listOf("0"))

      val expected = SearchResults(listOf(mapOf("id" to "1000")), null)
      val actual = searchService.search(rootPrefix, listOf(idField), searchNode)

      assertEquals(expected, actual)
    }

    @Test
    fun `range search by age in years returns results from entire year`() {
      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "1100",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = facilityId,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH))

      setCollectedDates(1000 to "2018-01-01", 1001 to "2019-12-31", 1100 to "2020-01-01")

      val searchNode = FieldNode(ageYearsField, listOf("1", "2"), SearchFilterType.Range)
      val sortField = SearchSortField(ageYearsField)

      val expected = SearchResults(listOf(mapOf("id" to "1001"), mapOf("id" to "1000")), null)
      val actual = searchService.search(rootPrefix, listOf(idField), searchNode, listOf(sortField))

      assertEquals(expected, actual)
    }

    @Test
    fun `range search with unbounded minimum age returns new matches`() {
      setCollectedDates(1000 to "2020-01-01", 1001 to "2020-05-01")

      // "Up to 2 months old"
      val searchNode = FieldNode(ageMonthsField, listOf(null, "2"), SearchFilterType.Range)

      val expected = SearchResults(listOf(mapOf("id" to "1001")), null)
      val actual = searchService.search(rootPrefix, listOf(idField), searchNode)

      assertEquals(expected, actual)
    }

    @Test
    fun `range search with unbounded maximum age returns old matches`() {
      setCollectedDates(1000 to "2020-01-01", 1001 to "2020-05-01")

      // "At least 3 months old"
      val searchNode = FieldNode(ageMonthsField, listOf("3", null), SearchFilterType.Range)

      val expected = SearchResults(listOf(mapOf("id" to "1000")), null)
      val actual = searchService.search(rootPrefix, listOf(idField), searchNode)

      assertEquals(expected, actual)
    }

    @Test
    fun `negative ages are not supported`() {
      val searchNode = FieldNode(ageMonthsField, listOf("-1"))

      assertThrows<IllegalArgumentException> {
        searchService.search(rootPrefix, listOf(idField), searchNode)
      }
    }

    private fun setCollectedDates(vararg dates: Pair<Int, String?>) {
      dates.forEach { (id, dateStr) ->
        val accession = accessionsDao.fetchOneById(AccessionId(id.toLong()))!!
        val collectedDate = dateStr?.let { LocalDate.parse(it) }
        accessionsDao.update(accession.copy(collectedDate = collectedDate))
      }
    }
  }

  @Nested
  inner class DateFieldSearchTest {
    @BeforeEach
    fun insertReceivedDateExamples() {
      listOf(1, 2, 8).forEach { day ->
        accessionsDao.insert(
            AccessionsRow(
                createdBy = user.userId,
                createdTime = Instant.now(),
                number = "JAN$day",
                facilityId = facilityId,
                modifiedBy = user.userId,
                modifiedTime = Instant.now(),
                stateId = AccessionState.Processing,
                receivedDate = LocalDate.of(2021, 1, day)))
      }
    }

    @Test
    fun `can search for exact date`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf("2021-01-02"), SearchFilterType.Exact)

      val expected = SearchResults(listOf(mapOf("id" to "2", "accessionNumber" to "JAN2")), null)
      val actual = accessionSearchService.search(facilityId, fields, searchNode)

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
      val actual = accessionSearchService.search(facilityId, fields, searchNode)

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
      val actual = accessionSearchService.search(facilityId, fields, searchNode, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `can search by date range with only minimum`() {
      val fields = listOf(accessionNumberField)
      val sortOrder = listOf(SearchSortField(receivedDateField))
      val searchNode =
          FieldNode(receivedDateField, listOf("2021-01-07", null), SearchFilterType.Range)

      val expected = SearchResults(listOf(mapOf("id" to "3", "accessionNumber" to "JAN8")), null)
      val actual = accessionSearchService.search(facilityId, fields, searchNode, sortOrder)

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
      val actual = accessionSearchService.search(facilityId, fields, searchNode, sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `date range with two nulls is rejected`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf(null, null), SearchFilterType.Range)

      assertThrows<IllegalArgumentException> {
        accessionSearchService.search(facilityId, fields, searchNode)
      }
    }

    @Test
    fun `malformed dates are rejected`() {
      val fields = listOf(accessionNumberField)
      val searchNode = FieldNode(receivedDateField, listOf("NOT_A_DATE"), SearchFilterType.Exact)

      assertThrows<IllegalArgumentException> {
        accessionSearchService.search(facilityId, fields, searchNode)
      }
    }
  }

  @Nested
  inner class ExistsFieldTest {
    private val nonSelectedTestId = ViabilityTestId(1)
    private val selectedTestId = ViabilityTestId(2)

    private val selectedField = rootPrefix.resolve("viabilityTests.selected")
    private val selectedFlattenedField = rootPrefix.resolve("viabilityTests_selected")
    private val testIdField = rootPrefix.resolve("viabilityTests.id")
    private val testIdFlattenedField = rootPrefix.resolve("viabilityTests_id")

    @BeforeEach
    fun insertViabilityTests() {
      viabilityTestsDao.insert(
          ViabilityTestsRow(
              nonSelectedTestId,
              AccessionId(1000),
              ViabilityTestType.Lab,
              remainingQuantity = BigDecimal.ONE,
              remainingUnitsId = SeedQuantityUnits.Seeds),
          ViabilityTestsRow(
              selectedTestId,
              AccessionId(1000),
              ViabilityTestType.Lab,
              remainingQuantity = BigDecimal.ONE,
              remainingUnitsId = SeedQuantityUnits.Seeds),
      )
      viabilityTestSelectionsDao.insert(
          ViabilityTestSelectionsRow(AccessionId(1000), selectedTestId))
    }

    @Test
    fun `returns true if nested target exists and false if not`() {
      val fields = listOf(accessionNumberField, selectedField, testIdField)
      val searchNode = NoConditionNode()
      val sortFields = listOf(SearchSortField(accessionNumberField), SearchSortField(selectedField))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "accessionNumber" to "ABCDEFG",
                  ),
                  mapOf(
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              mapOf("id" to "$nonSelectedTestId", "selected" to "false"),
                              mapOf("id" to "$selectedTestId", "selected" to "true"),
                          ),
                  ),
              ),
              null)

      val actual = searchService.search(rootPrefix, fields, searchNode, sortFields)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns true if flattened target exists and false if not`() {
      val fields = listOf(accessionNumberField, selectedFlattenedField, testIdFlattenedField)
      val searchNode = NoConditionNode()
      val sortFields =
          listOf(
              SearchSortField(accessionNumberField),
              SearchSortField(selectedFlattenedField, SearchDirection.Descending))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "accessionNumber" to "ABCDEFG",
                  ),
                  mapOf(
                      "accessionNumber" to "XYZ",
                      "viabilityTests_id" to "$selectedTestId",
                      "viabilityTests_selected" to "true",
                  ),
                  mapOf(
                      "accessionNumber" to "XYZ",
                      "viabilityTests_id" to "$nonSelectedTestId",
                      "viabilityTests_selected" to "false",
                  ),
              ),
              null)

      val actual = searchService.search(rootPrefix, fields, searchNode, sortFields)

      assertEquals(expected, actual)
    }

    @Test
    fun `searching for nonexistence filters out results where parent sublist does not exist`() {
      val fields = listOf(accessionNumberField, testIdField)
      val searchNode = FieldNode(selectedField, listOf("false"))
      val sortFields = listOf(SearchSortField(selectedField))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              // Both IDs are listed here because search filters control which
                              // top-level results are returned, not which sublist elements.
                              mapOf("id" to "$nonSelectedTestId"),
                              mapOf("id" to "$selectedTestId"),
                          ),
                  ),
                  // We don't expect the other accession because it has no tests.
                  ),
              null)

      val actual = searchService.search(rootPrefix, fields, searchNode, sortFields)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchValuesTest {
    @Test
    fun `no criteria for simple column value`() {
      val values = searchService.fetchValues(rootPrefix, speciesNameField, NoConditionNode())
      assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
    }

    @Test
    fun `renders null values as null, not as a string`() {
      accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(speciesId = null))
      val values = searchService.fetchValues(rootPrefix, speciesNameField, NoConditionNode())
      assertEquals(listOf("Other Dogwood", null), values)
    }

    @Test
    fun `fuzzy search of accession number`() {
      val values =
          searchService.fetchValues(
              rootPrefix,
              speciesNameField,
              FieldNode(accessionNumberField, listOf("xyzz"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Kousa Dogwood"), values)
    }

    @Test
    fun `prefix search of accession number`() {
      val values =
          searchService.fetchValues(
              rootPrefix,
              speciesNameField,
              FieldNode(accessionNumberField, listOf("a"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Other Dogwood"), values)
    }

    @Test
    fun `fuzzy search of text field in secondary table`() {
      val values =
          searchService.fetchValues(
              rootPrefix,
              speciesNameField,
              FieldNode(speciesNameField, listOf("dogwod"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
    }

    @Test
    fun `fuzzy search of text field in secondary table not in field list`() {
      val values =
          searchService.fetchValues(
              rootPrefix,
              stateField,
              FieldNode(speciesNameField, listOf("dogwod"), SearchFilterType.Fuzzy))
      assertEquals(listOf("Processed", "Processing"), values)
    }

    @Test
    fun `exact search of integer column value`() {
      val values =
          searchService.fetchValues(
              rootPrefix, treesCollectedFromField, FieldNode(treesCollectedFromField, listOf("1")))

      assertEquals(listOf("1"), values)
    }

    @Test
    fun `no criteria for computed column value`() {
      val values = searchService.fetchValues(rootPrefix, activeField, NoConditionNode())
      assertEquals(listOf("Active"), values)
    }

    @Test
    fun `can filter on computed column value`() {
      accessionsDao.update(
          accessionsDao.fetchOneById(AccessionId(1000))!!.copy(stateId = AccessionState.Withdrawn))
      val values =
          searchService.fetchValues(
              rootPrefix, activeField, FieldNode(activeField, listOf("Inactive")))
      assertEquals(listOf("Inactive"), values)
    }

    @Test
    fun `only includes values from accessions the user has permission to view`() {
      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              treesCollectedFrom = 3))

      val expected = listOf("1", "2")

      val actual = searchService.fetchValues(rootPrefix, treesCollectedFromField, NoConditionNode())

      assertEquals(expected, actual)
    }

    @Test
    fun `includes values from accessions at multiple facilities`() {
      every { user.facilityRoles } returns
          mapOf(facilityId to Role.MANAGER, FacilityId(1100) to Role.CONTRIBUTOR)

      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
              treesCollectedFrom = 3))

      val expected = listOf("1", "2", "3")

      val actual = searchService.fetchValues(rootPrefix, treesCollectedFromField, NoConditionNode())

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchAllValuesTest {
    @Test
    fun `does not return null for non-nullable field`() {
      val values = searchService.fetchAllValues(accessionNumberField, searchScopes)
      assertFalse(values.any { it == null }, "List of values should not contain null")
    }

    @Test
    fun `returns values for enum-mapped field`() {
      val expected = listOf(null) + ViabilityTestType.values().map { it.displayName }
      val values = searchService.fetchAllValues(viabilityTestsTypeField, searchScopes)
      assertEquals(expected, values)
    }

    @Test
    fun `returns values for free-text field on accession table`() {
      val expected = listOf(null, "Kousa Dogwood", "Other Dogwood")
      val values = searchService.fetchAllValues(speciesNameField, searchScopes)
      assertEquals(expected, values)
    }

    @Test
    fun `returns values for field from reference table`() {
      storageLocationsDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1000),
              facilityId = facilityId,
              name = "Refrigerator 1",
              conditionId = StorageCondition.Refrigerator,
              createdBy = user.userId,
              createdTime = Instant.now(),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ))
      storageLocationsDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1001),
              facilityId = facilityId,
              name = "Freezer 1",
              conditionId = StorageCondition.Freezer,
              createdBy = user.userId,
              createdTime = Instant.now(),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ))
      storageLocationsDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1002),
              facilityId = facilityId,
              name = "Freezer 2",
              conditionId = StorageCondition.Freezer,
              createdBy = user.userId,
              createdTime = Instant.now(),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ))

      val expected = listOf(null, "Freezer 1", "Freezer 2", "Refrigerator 1")
      val values = searchService.fetchAllValues(storageLocationNameField, searchScopes)
      assertEquals(expected, values)
    }

    @Test
    fun `only includes storage locations the user has permission to view`() {
      insertFacility(1100)

      storageLocationsDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1000),
              facilityId = FacilityId(100),
              name = "Facility 100 fridge",
              conditionId = StorageCondition.Refrigerator,
              createdBy = user.userId,
              createdTime = Instant.now(),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ))
      storageLocationsDao.insert(
          StorageLocationsRow(
              id = StorageLocationId(1001),
              facilityId = FacilityId(1100),
              name = "Facility 1100 fridge",
              conditionId = StorageCondition.Refrigerator,
              createdBy = user.userId,
              createdTime = Instant.now(),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ))

      val expected = listOf(null, "Facility 100 fridge")

      val actual = searchService.fetchAllValues(storageLocationNameField, searchScopes)

      assertEquals(expected, actual)
    }

    @Test
    fun `only includes accession values the user has permission to view`() {
      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
              treesCollectedFrom = 3))

      val expected = listOf(null, "1", "2")

      val actual = searchService.fetchAllValues(treesCollectedFromField, searchScopes)

      assertEquals(expected, actual)
    }

    @Test
    fun `only includes child table values the user has permission to view`() {
      val hiddenAccessionId = AccessionId(1100)

      insertFacility(1100)

      accessionsDao.insert(
          AccessionsRow(
              id = hiddenAccessionId,
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
              treesCollectedFrom = 3))

      listOf(1000, 1100).forEach { id ->
        val accessionId = AccessionId(id.toLong())
        val testId = ViabilityTestId(id.toLong())

        viabilityTestsDao.insert(
            ViabilityTestsRow(
                id = testId,
                accessionId = accessionId,
                testType = ViabilityTestType.Lab,
                remainingQuantity = BigDecimal.ONE,
                remainingUnitsId = SeedQuantityUnits.Grams,
                seedsSown = id))
        viabilityTestResultsDao.insert(
            ViabilityTestResultsRow(
                testId = testId, recordingDate = LocalDate.EPOCH, seedsGerminated = id))
      }

      assertEquals(
          listOf(null, "1000"),
          searchService.fetchAllValues(viabilityTestResultsSeedsGerminatedField, searchScopes),
          "Value from viability_test_results table (grandchild of accessions)")

      assertEquals(
          listOf(null, "1000"),
          searchService.fetchAllValues(viabilityTestSeedsSownField, searchScopes),
          "Value from viability_tests table (child of accessions)")
    }

    @Test
    fun `only includes child table values governed by organization search scope`() {
      every { user.facilityRoles } returns
          mapOf(
              facilityId to Role.CONTRIBUTOR,
              FacilityId(1100) to Role.CONTRIBUTOR,
              FacilityId(2200) to Role.OWNER)

      insertFacility(1100)

      val otherOrganizationId = OrganizationId(5)
      insertOrganization(otherOrganizationId)
      insertFacility(2200, otherOrganizationId)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherFacility",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ),
          AccessionsRow(
              id = AccessionId(2200),
              number = "OtherOrg",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(2200),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ))

      val expectedScopedOrg = listOf("ABCDEFG", "OtherFacility", "XYZ")
      val expectedScopedOtherOrg = listOf("OtherOrg")

      assertEquals(
          expectedScopedOrg,
          searchService.fetchAllValues(accessionNumberField, searchScopes),
          "Expected values for organization $organizationId only.")
      assertEquals(
          expectedScopedOtherOrg,
          searchService.fetchAllValues(
              accessionNumberField, listOf(OrganizationIdScope(otherOrganizationId))),
          "Expected values for organization $otherOrganizationId only.")
    }

    @Test
    fun `only includes child table values governed by facility search scope`() {
      every { user.facilityRoles } returns
          mapOf(
              facilityId to Role.CONTRIBUTOR,
              FacilityId(1100) to Role.CONTRIBUTOR,
              FacilityId(2200) to Role.OWNER)

      insertFacility(1100)

      val otherOrganizationId = OrganizationId(5)
      insertOrganization(otherOrganizationId)
      insertFacility(2200)

      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(1100),
              number = "OtherProject",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(1100),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ),
          AccessionsRow(
              id = AccessionId(2200),
              number = "OtherProject22",
              stateId = AccessionState.Processed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              facilityId = FacilityId(2200),
              modifiedBy = user.userId,
              modifiedTime = Instant.now(),
          ))

      val expectedScopedFacility = listOf("OtherProject22")

      assertEquals(
          expectedScopedFacility,
          searchService.fetchAllValues(
              accessionNumberField, listOf(FacilityIdScope(FacilityId(2200)))),
          "Expected values for facility 2200 only.")
    }

    @Test
    fun `throws exception if search scopes is empty`() {
      assertThrows<IllegalArgumentException> {
        searchService.fetchAllValues(accessionNumberField, emptyList())
      }
    }
  }

  @Test
  fun `search only includes accessions at facilities the user has permission to view`() {
    // A facility in an org the user isn't in
    insertOrganization(2)
    insertFacility(2000)

    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(2000),
            number = "OtherOrg",
            stateId = AccessionState.Processed,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            facilityId = FacilityId(2000),
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
        ))

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
        accessionSearchService.search(
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
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `search only includes results from requested facility`() {
    every { user.facilityRoles } returns
        mapOf(facilityId to Role.MANAGER, FacilityId(1100) to Role.CONTRIBUTOR)

    insertFacility(1100)

    accessionsDao.insert(
        AccessionsRow(
            id = AccessionId(1100),
            number = "OtherProject",
            stateId = AccessionState.Processed,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            facilityId = FacilityId(1100),
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
        ))

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
        accessionSearchService.search(
            facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }

  @Nested
  inner class UserSearchTest {
    private val organizationsPrefix = SearchFieldPrefix(tables.organizations)
    private val usersPrefix = SearchFieldPrefix(tables.users)

    private val otherOrganizationId = OrganizationId(2)
    private val bothOrgsUserId = UserId(4)
    private val otherOrgUserId = UserId(5)
    private val deviceManagerUserId = UserId(6)

    @BeforeEach
    fun insertOtherUsers() {
      insertUser(deviceManagerUserId, type = UserType.DeviceManager)
      insertUser(bothOrgsUserId)
      insertUser(otherOrgUserId)

      insertOrganization(otherOrganizationId)

      insertOrganizationUser(deviceManagerUserId)
      insertOrganizationUser(bothOrgsUserId, role = Role.ADMIN)
      insertOrganizationUser(bothOrgsUserId, otherOrganizationId, Role.ADMIN)
      insertOrganizationUser(otherOrgUserId, otherOrganizationId)
    }

    @Test
    fun `should not see inaccessible organizations of other users`() {
      val roleField = organizationsPrefix.resolve("members.roleName")
      val userIdField = organizationsPrefix.resolve("members.user.id")
      val userOrganizationIdField =
          organizationsPrefix.resolve("members.user.organizationMemberships.organization.id")

      val fields = listOf(userIdField, userOrganizationIdField, roleField)
      val sortOrder = fields.map { SearchSortField(it) }

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "members" to
                          listOf(
                              mapOf(
                                  "roleName" to "Manager",
                                  "user" to
                                      mapOf(
                                          "id" to "${user.userId}",
                                          "organizationMemberships" to
                                              listOf(
                                                  mapOf(
                                                      "organization" to
                                                          mapOf("id" to "$organizationId"),
                                                  ),
                                              ),
                                      ),
                              ),
                              mapOf(
                                  "roleName" to "Admin",
                                  "user" to
                                      mapOf(
                                          "id" to "$bothOrgsUserId",
                                          "organizationMemberships" to
                                              listOf(
                                                  mapOf(
                                                      "organization" to
                                                          mapOf("id" to "$organizationId")))))))),
              null)

      val actual = searchService.search(organizationsPrefix, fields, NoConditionNode(), sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `should not see inaccessible organizations of other users via flattened sublists`() {
      val userIdField = usersPrefix.resolve("id")
      val userOrganizationIdField = usersPrefix.resolve("organizationMemberships_organization_id")

      val fields = listOf(userIdField, userOrganizationIdField)
      val sortOrder = fields.map { SearchSortField(it) }

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "${user.userId}",
                      "organizationMemberships_organization_id" to "$organizationId",
                  ),
                  mapOf(
                      "id" to "$bothOrgsUserId",
                      "organizationMemberships_organization_id" to "$organizationId",
                  )),
              null)

      val actual = searchService.search(usersPrefix, fields, NoConditionNode(), sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `should not see users with no mutual organizations`() {
      val userIdField = usersPrefix.resolve("id")
      val fields = listOf(userIdField)
      val sortOrder = fields.map { SearchSortField(it) }

      val expected =
          SearchResults(
              listOf(mapOf("id" to "${user.userId}"), mapOf("id" to "$bothOrgsUserId")), null)

      val actual = searchService.search(usersPrefix, fields, NoConditionNode(), sortOrder)

      assertEquals(expected, actual)
    }

    @Test
    fun `should be able to filter organization members by organization`() {
      val membersPrefix = SearchFieldPrefix(tables.organizationUsers)
      val organizationIdField = membersPrefix.resolve("organization_id")
      val roleNameField = membersPrefix.resolve("roleName")

      val fields = listOf(organizationIdField, roleNameField)
      val criteria =
          AndNode(
              listOf(
                  FieldNode(roleNameField, listOf(Role.ADMIN.displayName)),
                  FieldNode(organizationIdField, listOf("$organizationId"))))
      val sortOrder = fields.map { SearchSortField(it) }

      insertOrganizationUser(organizationId = otherOrganizationId, role = Role.ADMIN)
      every { user.organizationRoles } returns
          mapOf(
              organizationId to Role.ADMIN,
              otherOrganizationId to Role.ADMIN,
          )

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "organization_id" to "$organizationId",
                      "roleName" to Role.ADMIN.displayName,
                  )),
              null)

      val actual = searchService.search(membersPrefix, fields, criteria, sortOrder)
      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class NestedFieldsTest {
    private val bagsNumberField = rootPrefix.resolve("bags.number")
    private val facilityNameField = rootPrefix.resolve("facility.name")
    private val seedsSownField = rootPrefix.resolve("viabilityTests.seedsSown")
    private val seedsGerminatedField =
        rootPrefix.resolve("viabilityTests.viabilityTestResults.seedsGerminated")
    private val testTypeField = rootPrefix.resolve("viabilityTests.type")

    private val bagsTable = tables.bags
    private val viabilityTestResultsTable = tables.viabilityTestResults

    private lateinit var testId: ViabilityTestId

    @BeforeEach
    fun insertNestedData() {
      bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "1"))
      bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "5"))
      bagsDao.insert(BagsRow(accessionId = AccessionId(1001), bagNumber = "2"))
      bagsDao.insert(BagsRow(accessionId = AccessionId(1001), bagNumber = "6"))

      val viabilityTestResultsRow =
          ViabilityTestsRow(
              accessionId = AccessionId(1000),
              testType = ViabilityTestType.Lab,
              seedsSown = 15,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Grams)

      viabilityTestsDao.insert(viabilityTestResultsRow)

      testId = viabilityTestResultsRow.id!!

      viabilityTestResultsDao.insert(
          ViabilityTestResultsRow(
              testId = testId, recordingDate = LocalDate.EPOCH, seedsGerminated = 5))
      viabilityTestResultsDao.insert(
          ViabilityTestResultsRow(
              testId = testId, recordingDate = LocalDate.EPOCH.plusDays(1), seedsGerminated = 10))
    }

    @Test
    fun `does not return empty top-level results when only selecting sublist fields`() {
      // Use searchService rather than accessionSearchService; we don't want to include the
      // accession ID/number fields because those would cause the results for accession 1001 to
      // no longer be empty.
      val result = searchService.search(rootPrefix, listOf(seedsSownField), NoConditionNode())

      val expected =
          SearchResults(
              listOf(mapOf("viabilityTests" to listOf(mapOf("seedsSown" to "15")))), cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can get to accession sublists starting from organization`() {
      val orgPrefix = SearchFieldPrefix(tables.organizations)
      val fullyQualifiedField = orgPrefix.resolve("facilities.accessions.bags.number")

      val result =
          searchService.search(
              orgPrefix,
              listOf(fullyQualifiedField),
              FieldNode(fullyQualifiedField, listOf("5")),
              listOf(SearchSortField(fullyQualifiedField)))

      // Bags from both accessions appear here even though we're filtering on bag number because the
      // filter criteria determine what top-level (root prefix) results are returned, and we always
      // return the full set of data for top-level results that match the criteria.
      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "facilities" to
                          listOf(
                              mapOf(
                                  "accessions" to
                                      listOf(
                                          mapOf(
                                              "bags" to
                                                  listOf(
                                                      mapOf("number" to "1"),
                                                      mapOf("number" to "5"))),
                                          mapOf(
                                              "bags" to
                                                  listOf(
                                                      mapOf("number" to "2"),
                                                      mapOf("number" to "6")))))))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can select aliases for flattened sublist fields from within nested sublists`() {
      val prefix = SearchFieldPrefix(tables.facilities)
      val field = prefix.resolve("accessions.bagNumber")

      val result =
          searchService.search(
              prefix, listOf(field), NoConditionNode(), listOf(SearchSortField(field)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "accessions" to
                          listOf(
                              mapOf("bagNumber" to "1"),
                              mapOf("bagNumber" to "2"),
                              mapOf("bagNumber" to "5"),
                              mapOf("bagNumber" to "6"),
                          ))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can sort by aliases for flattened sublist fields from within nested sublists`() {
      val prefix = SearchFieldPrefix(tables.facilities)
      val idField = prefix.resolve("accessions.id")
      val aliasField = prefix.resolve("accessions.bagNumber")

      val result =
          searchService.search(
              prefix, listOf(idField), NoConditionNode(), listOf(SearchSortField(aliasField)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "accessions" to
                          listOf(
                              // 4 entries here because we're bringing a flattened sublist into the
                              // picture, even though it's only on a sort field. Unclear if this is
                              // the desirable result or not, but it's at least a predictable one.
                              mapOf("id" to "1000"),
                              mapOf("id" to "1001"),
                              mapOf("id" to "1000"),
                              mapOf("id" to "1001"),
                          ))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can get to organization from accession sublist`() {
      val viabilityTestResultsPrefix = SearchFieldPrefix(tables.viabilityTestResults)
      val rootSeedsGerminatedField = viabilityTestResultsPrefix.resolve("seedsGerminated")
      val orgNameField =
          viabilityTestResultsPrefix.resolve("viabilityTest.accession.facility.organization.name")
      val orgName = "Organization $organizationId"

      val result =
          searchService.search(
              viabilityTestResultsPrefix,
              listOf(orgNameField, rootSeedsGerminatedField),
              FieldNode(orgNameField, listOf(orgName)))

      val sublistValues =
          mapOf(
              "accession" to mapOf("facility" to mapOf("organization" to mapOf("name" to orgName))))

      val expected =
          SearchResults(
              listOf(
                  mapOf("viabilityTest" to sublistValues, "seedsGerminated" to "5"),
                  mapOf("viabilityTest" to sublistValues, "seedsGerminated" to "10")),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can get to flattened organization from accession sublist`() {
      val viabilityTestResultsPrefix = SearchFieldPrefix(tables.viabilityTestResults)
      val rootSeedsGerminatedField = viabilityTestResultsPrefix.resolve("seedsGerminated")
      val flattenedFieldName = "viabilityTest_accession_facility_organization_name"
      val orgNameField = viabilityTestResultsPrefix.resolve(flattenedFieldName)
      val orgName = "Organization $organizationId"

      val result =
          searchService.search(
              viabilityTestResultsPrefix,
              listOf(orgNameField, rootSeedsGerminatedField),
              FieldNode(orgNameField, listOf(orgName)))

      val expected =
          SearchResults(
              listOf(
                  mapOf(flattenedFieldName to orgName, "seedsGerminated" to "5"),
                  mapOf(flattenedFieldName to orgName, "seedsGerminated" to "10")),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can filter across multiple single-value sublists`() {
      val viabilityTestResultsPrefix = SearchFieldPrefix(tables.viabilityTestResults)
      val orgNameField =
          viabilityTestResultsPrefix.resolve("viabilityTest.accession.facility.organization.name")

      val result =
          searchService.search(
              viabilityTestResultsPrefix,
              listOf(orgNameField),
              FieldNode(orgNameField, listOf("Non-matching organization name")))

      val expected = SearchResults(emptyList(), cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can filter on nested field`() {
      val fields = listOf(bagsNumberField)
      val sortOrder = fields.map { SearchSortField(it) }

      val result =
          accessionSearchService.search(
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
          accessionSearchService.search(
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
          accessionSearchService.search(
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
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "viabilityTestResults" to
                                      listOf(
                                          mapOf("seedsGerminated" to "10"),
                                          mapOf("seedsGerminated" to "5")))))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can sort on grandchild field that is not in list of query fields`() {
      val fields = listOf(accessionNumberField)

      val result =
          accessionSearchService.search(
              facilityId,
              fields,
              NoConditionNode(),
              listOf(SearchSortField(seedsGerminatedField, SearchDirection.Descending)))

      val expected =
          SearchResults(
              listOf(
                  mapOf("id" to "1000", "accessionNumber" to "XYZ"),
                  mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can specify duplicate sort fields`() {
      val fields = listOf(seedsGerminatedField)

      val result =
          accessionSearchService.search(
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
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "viabilityTestResults" to
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
          accessionSearchService.search(
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

      viabilityTestsDao.insert(
          ViabilityTestsRow(
              accessionId = AccessionId(1000),
              testType = ViabilityTestType.Nursery,
              seedsSown = 1,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Grams))

      val result =
          accessionSearchService.search(
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
                      "viabilityTests" to
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

      val result = accessionSearchService.search(facilityId, fields, criteria = NoConditionNode())

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
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "seedsSown" to "15",
                                  "viabilityTestResults" to
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
          accessionSearchService.search(
              facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "viabilityTestResults" to
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
    fun `can specify a flattened sublist as a child of a nested sublist`() {
      val field = rootPrefix.resolve("viabilityTests.viabilityTestResults_seedsGerminated")
      val fields = listOf(field)
      val sortOrder = fields.map { SearchSortField(it) }

      val result =
          accessionSearchService.search(
              facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              mapOf("viabilityTestResults_seedsGerminated" to "5"),
                              mapOf("viabilityTestResults_seedsGerminated" to "10"),
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
    fun `cannot specify a flattened sublist with nested children`() {
      assertThrows<IllegalArgumentException> {
        rootPrefix.resolve("viabilityTests_viabilityTestResults.seedsGerminated")
      }
    }

    @Test
    fun `can sort on nested field that is not in list of query fields`() {
      val sortOrder = listOf(SearchSortField(bagsNumberField, SearchDirection.Descending))

      val result =
          accessionSearchService.search(facilityId, emptyList(), NoConditionNode(), sortOrder)

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
    fun `can sort on flattened field that is not in list of query fields`() {
      val sortOrder = listOf(SearchSortField(bagNumberFlattenedField, SearchDirection.Descending))

      val result =
          accessionSearchService.search(facilityId, emptyList(), NoConditionNode(), sortOrder)

      val expected =
          SearchResults(
              listOf(
                  // Bag 6
                  mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
                  // Bag 5
                  mapOf("id" to "1000", "accessionNumber" to "XYZ"),
                  // Bag 2
                  mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
                  // Bag 1
                  mapOf("id" to "1000", "accessionNumber" to "XYZ"),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `returns nested result for each row when querying non-nested child field`() {
      val fields = listOf(viabilityTestResultsSeedsGerminatedField, seedsGerminatedField)
      val sortOrder = fields.map { SearchSortField(it) }
      val criteria = FieldNode(seedsGerminatedField, listOf("1", "100"), SearchFilterType.Range)

      val result = accessionSearchService.search(facilityId, fields, criteria, sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "viabilityTestResults" to
                                      listOf(
                                          mapOf("seedsGerminated" to "5"),
                                          mapOf("seedsGerminated" to "10")))),
                      "viabilityTests_viabilityTestResults_seedsGerminated" to "5",
                  ),
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "viabilityTestResults" to
                                      listOf(
                                          mapOf("seedsGerminated" to "5"),
                                          mapOf("seedsGerminated" to "10")))),
                      "viabilityTests_viabilityTestResults_seedsGerminated" to "10",
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `returns single-value sublists as maps rather than lists`() {
      val fields = listOf(facilityNameField)
      val sortOrder = listOf(SearchSortField(facilityNameField))
      val criteria = FieldNode(accessionNumberField, listOf("XYZ"))

      val result = accessionSearchService.search(facilityId, fields, criteria, sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "facility" to mapOf("name" to "Facility $facilityId"))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can navigate up and down table hierarchy`() {
      val seedsSownViaResults =
          rootPrefix.resolve("viabilityTests.viabilityTestResults.viabilityTest.seedsSown")
      val fields = listOf(seedsSownViaResults)
      val sortOrder = listOf(SearchSortField(seedsSownViaResults))
      val criteria = FieldNode(accessionNumberField, listOf("XYZ"))

      val result = accessionSearchService.search(facilityId, fields, criteria, sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "viabilityTestResults" to
                                      listOf(
                                          // There are two test results. We want to make sure we
                                          // can navigate back up from them even if we don't select
                                          // any fields from them.
                                          mapOf("viabilityTest" to mapOf("seedsSown" to "15")),
                                          mapOf("viabilityTest" to mapOf("seedsSown" to "15")),
                                      ))),
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can reference same field using two different paths`() {
      val seedsSownViaResults =
          rootPrefix.resolve("viabilityTests.viabilityTestResults.viabilityTest.seedsSown")
      val fields = listOf(seedsSownField, seedsSownViaResults)
      val sortOrder = listOf(SearchSortField(seedsSownViaResults))
      val criteria =
          AndNode(
              listOf(
                  FieldNode(seedsSownViaResults, listOf("15")),
                  FieldNode(seedsSownField, listOf("15"))))

      val result = accessionSearchService.search(facilityId, fields, criteria, sortOrder)

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1000",
                      "accessionNumber" to "XYZ",
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "seedsSown" to "15",
                                  "viabilityTestResults" to
                                      listOf(
                                          mapOf("viabilityTest" to mapOf("seedsSown" to "15")),
                                          mapOf("viabilityTest" to mapOf("seedsSown" to "15")),
                                      ))),
                  ),
              ),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can include a computed field and its underlying raw field in a nested sublist`() {
      val prefix = SearchFieldPrefix(tables.organizations)
      val activeField = prefix.resolve("facilities.accessions.active")
      val stateField = prefix.resolve("facilities.accessions.state")

      val result = searchService.search(prefix, listOf(stateField, activeField), NoConditionNode())

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "facilities" to
                          listOf(
                              mapOf(
                                  "accessions" to
                                      listOf(
                                          mapOf("active" to "Active", "state" to "Processing"),
                                          mapOf("active" to "Active", "state" to "Processed"),
                                      ))))),
              cursor = null)

      assertEquals(expected, result)
    }

    @Test
    fun `can search all the fields`() {
      val prefix = SearchFieldPrefix(tables.organizations)
      val organizationFieldNames = tables.organizations.getAllFieldNames()

      // getAllFieldNames() doesn't visit single-value sublists since they are usually parent
      // entities and we want to avoid infinite recursion. But the "user" sublist under
      // the organization users tables is a special case: a single-value sublist that
      // refers to a child, not a parent. Include it explicitly.
      val usersFieldNames = tables.users.getAllFieldNames("members.user.")

      val fields = (organizationFieldNames + usersFieldNames).sorted().map { prefix.resolve(it) }

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

      val expectedAccessions =
          listOf(
                  mapOf(
                      "accessionNumber" to "ABCDEFG",
                      "active" to "Active",
                      "bags" to listOf(mapOf("number" to "2"), mapOf("number" to "6")),
                      "id" to "1001",
                      "speciesName" to "Other Dogwood",
                      "state" to "Processing",
                      "treesCollectedFrom" to "2",
                  ),
                  mapOf(
                      "accessionNumber" to "XYZ",
                      "active" to "Active",
                      "ageMonths" to "15",
                      "ageYears" to "1",
                      "checkedInTime" to "$checkedInTime",
                      "collectedDate" to "2019-03-02",
                      "collectors" to
                          listOf(
                              mapOf("name" to "primary", "position" to "0"),
                              mapOf("name" to "secondary 1", "position" to "1"),
                              mapOf("name" to "secondary 2", "position" to "2"),
                          ),
                      "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                      "id" to "1000",
                      "primaryCollectorName" to "primary",
                      "primaryCollectors" to
                          listOf(
                              mapOf("name" to "primary", "position" to "0"),
                          ),
                      "secondaryCollectors" to
                          listOf(
                              mapOf("name" to "secondary 1", "position" to "1"),
                              mapOf("name" to "secondary 2", "position" to "2"),
                          ),
                      "speciesName" to "Kousa Dogwood",
                      "state" to "Processed",
                      "treesCollectedFrom" to "1",
                      "viabilityTests" to
                          listOf(
                              mapOf(
                                  "viabilityTestResults" to
                                      listOf(
                                          mapOf(
                                              "recordingDate" to "1970-01-01",
                                              "seedsGerminated" to "5"),
                                          mapOf(
                                              "recordingDate" to "1970-01-02",
                                              "seedsGerminated" to "10")),
                                  "id" to "$testId",
                                  "type" to "Lab",
                                  "seedsSown" to "15",
                                  "selected" to "false",
                              ),
                          ),
                  ))
              .flatMap { base ->
                base.getListValue("bags")?.map { bag ->
                  val perBagNumber = base.toMutableMap()
                  perBagNumber.putIfNotNull("bagNumber", bag["number"])
                  perBagNumber
                }
                    ?: listOf(base)
              }

      val expectedSpecies =
          listOf(
              mapOf(
                  "commonName" to "Common 1",
                  "growthForm" to "Graminoid",
                  "id" to "10000",
                  "rare" to "false",
                  "scientificName" to "Kousa Dogwood",
              ),
              mapOf(
                  "commonName" to "Common 2",
                  "endangered" to "true",
                  "id" to "10001",
                  "scientificName" to "Other Dogwood",
                  "seedStorageBehavior" to "Orthodox",
              ),
          )

      val expectedFacilities =
          listOf(
              mapOf(
                  "accessions" to expectedAccessions,
                  "connectionState" to "Not Connected",
                  "createdTime" to "1970-01-01T00:00:00Z",
                  "description" to "Description 100",
                  "id" to "100",
                  "name" to "Facility 100",
                  "type" to "Seed Bank",
              ))

      val expectedUser =
          mapOf(
              "createdTime" to "1970-01-01T00:00:00Z",
              "email" to "2@terraformation.com",
              "firstName" to "First",
              "id" to "2",
              "lastName" to "Last",
              "organizationMemberships" to
                  listOf(
                      mapOf(
                          "createdTime" to "1970-01-01T00:00:00Z",
                          "roleName" to "Manager",
                      )))

      val expectedOrganizationUsers =
          listOf(
              mapOf(
                  "createdTime" to "1970-01-01T00:00:00Z",
                  "roleName" to "Manager",
                  "user" to expectedUser,
              ))

      val expected =
          listOf(
              mapOf(
                  "createdTime" to "1970-01-01T00:00:00Z",
                  "facilities" to expectedFacilities,
                  "id" to "1",
                  "name" to "Organization 1",
                  "members" to expectedOrganizationUsers,
                  "species" to expectedSpecies,
              ))

      val result = searchService.search(prefix, fields, NoConditionNode())

      assertNull(result.cursor)

      if (expected != result.results) {
        // Pretty-print both values so they are easy to diff.
        val objectMapper =
            jacksonObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT)
        assertEquals(
            objectMapper.writeValueAsString(expected),
            objectMapper.writeValueAsString(result.results))
      }
    }

    @Test
    fun `all fields are valid sort keys`() {
      val prefix = SearchFieldPrefix(tables.organizations)

      val expected = listOf(mapOf("id" to "1"))
      val searchFields = listOf(prefix.resolve("id"))

      prefix.searchTable.getAllFieldNames().forEach { fieldName ->
        val field = prefix.resolve(fieldName)
        val sortFields = listOf(SearchSortField(field))
        assertDoesNotThrow("Sort by $fieldName") {
          val result = searchService.search(prefix, searchFields, NoConditionNode(), sortFields)
          assertEquals(expected, result.results, "Sort by $fieldName")
        }
      }
    }

    @Test
    fun `can search a child table with a parent table field`() {
      val prefix = SearchFieldPrefix(root = bagsTable)
      val bagNumberField = prefix.resolve("number")
      val accessionNumberField = prefix.resolve("accession.accessionNumber")
      val fields = listOf(bagNumberField, accessionNumberField)
      val criteria = FieldNode(bagNumberField, listOf("5"))

      val expected =
          SearchResults(
              listOf(mapOf("number" to "5", "accession" to mapOf("accessionNumber" to "XYZ"))),
              cursor = null)

      val result = searchService.search(prefix, fields, criteria)

      assertEquals(expected, result)
    }

    @Test
    fun `can search a child table without a parent`() {
      val prefix = SearchFieldPrefix(root = bagsTable)
      val bagNumberField = prefix.resolve("number")
      val fields = listOf(bagNumberField)
      val criteria = FieldNode(bagNumberField, listOf("5"))

      val expected = SearchResults(listOf(mapOf("number" to "5")), cursor = null)

      val result = searchService.search(prefix, fields, criteria)

      assertEquals(expected, result)
    }

    @Test
    fun `searching a child table only returns results the user has permission to see`() {
      val prefix = SearchFieldPrefix(root = bagsTable)
      val bagNumberField = prefix.resolve("number")
      val fields = listOf(bagNumberField)
      val criteria = NoConditionNode()
      val order = listOf(SearchSortField(bagNumberField))

      // A facility in an org the user isn't in
      val otherFacilityId = FacilityId(2000)
      insertOrganization(2)
      insertFacility(otherFacilityId)

      accessionsDao.update(
          accessionsDao.fetchOneById(AccessionId(1000))!!.copy(facilityId = otherFacilityId))

      val expected =
          SearchResults(listOf(mapOf("number" to "2"), mapOf("number" to "6")), cursor = null)

      val result = searchService.search(prefix, fields, criteria, order)

      assertEquals(expected, result)
    }

    @Test
    fun `permission check can join multiple parent tables`() {
      val prefix = SearchFieldPrefix(root = viabilityTestResultsTable)
      val seedsGerminatedField = prefix.resolve("seedsGerminated")
      val fields = listOf(seedsGerminatedField)
      val criteria = NoConditionNode()
      val order = listOf(SearchSortField(seedsGerminatedField))

      // A facility in an org the user isn't in
      val otherFacilityId = FacilityId(2000)
      insertOrganization(2)
      insertFacility(otherFacilityId)

      accessionsDao.update(
          accessionsDao.fetchOneById(AccessionId(1000))!!.copy(facilityId = otherFacilityId))

      val viabilityTestResultsRow =
          ViabilityTestsRow(
              accessionId = AccessionId(1001),
              testType = ViabilityTestType.Lab,
              seedsSown = 50,
              remainingQuantity = BigDecimal(30),
              remainingUnitsId = SeedQuantityUnits.Pounds)

      viabilityTestsDao.insert(viabilityTestResultsRow)
      viabilityTestResultsDao.insert(
          ViabilityTestResultsRow(
              testId = viabilityTestResultsRow.id!!,
              recordingDate = LocalDate.EPOCH,
              seedsGerminated = 8))

      val expected = SearchResults(listOf(mapOf("seedsGerminated" to "8")), cursor = null)

      val result = searchService.search(prefix, fields, criteria, order)

      assertEquals(expected, result)
    }
  }
}
