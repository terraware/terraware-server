package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceBasicSearchTest : SearchServiceTest() {
  private val countryPrefix = SearchFieldPrefix(tables.countries)
  private val countryCodeField = countryPrefix.resolve("code")
  private val countryNameField = countryPrefix.resolve("name")

  @Test
  fun `returns full set of values from child field when filtering on that field`() {
    val fields = listOf(bagNumberField)
    val sortOrder = fields.map { SearchSortField(it) }

    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = AccessionId(1000), bagNumber = "B"))

    val result =
        searchAccessions(
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
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

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
  fun `honors sort order`() {
    val fields =
        listOf(speciesNameField, accessionNumberField, treesCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it, SearchDirection.Descending) }

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

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
  fun `sorts text fields based on locale`() {
    insertSpecies(scientificName = "Å x")

    val prefix = SearchFieldPrefix(tables.species)
    val field = prefix.resolve("scientificName")
    val sortOrder = SearchSortField(field)

    val englishExpected =
        SearchResults(
            listOf(
                // English ICU collation strips off diacriticals
                mapOf("scientificName" to "Å x"),
                mapOf("scientificName" to "Kousa Dogwood"),
                mapOf("scientificName" to "Other Dogwood"),
            ),
            cursor = null)

    val swedishExpected =
        SearchResults(
            listOf(
                // Swedish ICU collation puts Å after Z
                mapOf("scientificName" to "Kousa Dogwood"),
                mapOf("scientificName" to "Other Dogwood"),
                mapOf("scientificName" to "Å x"),
            ),
            cursor = null)

    val englishResult =
        Locale.ENGLISH.use {
          searchService.search(prefix, listOf(field), NoConditionNode(), listOf(sortOrder))
        }
    val swedishResult =
        Locale.forLanguageTag("se").use {
          searchService.search(prefix, listOf(field), NoConditionNode(), listOf(sortOrder))
        }

    assertEquals(englishExpected, englishResult, "English should put Å before K")
    assertEquals(swedishExpected, swedishResult, "Swedish should put Å after O")
  }

  @Test
  fun `can filter on computed fields whose raw values are being queried`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(stateId = AccessionState.UsedUp))

    val fields = listOf(accessionNumberField, stateField)
    val searchNode = FieldNode(activeField, listOf("Inactive"))

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("id" to "1000", "accessionNumber" to "XYZ", "state" to "Used Up")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `exact search on text fields is case- and accent-insensitive`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneById(AccessionId(1001))!!
            .copy(processingNotes = "Sómé Mätching Nótes"))

    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(processingNotesField, listOf("some matching Notés"), SearchFilterType.Exact)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `exact search on localizable text fields is case-insensitive`() {
    val gibberishValue = "United States".toGibberish()
    val searchNode =
        FieldNode(countryNameField, listOf(gibberishValue.lowercase()), SearchFilterType.Exact)

    val result =
        Locales.GIBBERISH.use {
          searchService.search(
              countryPrefix, listOf(countryCodeField, countryNameField), searchNode)
        }

    val expected =
        SearchResults(listOf(mapOf("code" to "US", "name" to gibberishValue)), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `exact search on localizable text fields is accent-insensitive`() {
    val searchNode = FieldNode(countryNameField, listOf("cote d’ivoire"), SearchFilterType.Exact)

    val result = searchService.search(countryPrefix, listOf(countryNameField), searchNode)

    val expected = SearchResults(listOf(mapOf("name" to "Côte d’Ivoire")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `localizable text fields are sorted by their localized strings`() {
    // Togo sorts before United States in English, but after it in gibberish because gibberish
    // reverses the word order.
    val unitedStates = "United States".toGibberish()
    val togo = "Togo".toGibberish()

    val searchNode = FieldNode(countryCodeField, listOf("TG", "US"))
    val sortField = SearchSortField(countryNameField)

    val result =
        Locales.GIBBERISH.use {
          searchService.search(
              countryPrefix, listOf(countryNameField), searchNode, listOf(sortField))
        }

    val expected =
        SearchResults(
            listOf(
                mapOf("name" to unitedStates),
                mapOf("name" to togo),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can search for timestamps using different but equivalent RFC 3339 time format`() {
    val speciesCheckedTimeField = rootPrefix.resolve("species_checkedTime")
    val fields = listOf(speciesCheckedTimeField)
    val searchNode =
        FieldNode(
            speciesCheckedTimeField,
            listOf(checkedTimeString.replace("Z", ".000+00:00")),
            SearchFilterType.Exact)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1000",
                    "accessionNumber" to "XYZ",
                    "species_checkedTime" to checkedTimeString)),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `search only includes results from requested facility`() {
    every { user.facilityRoles } returns
        mapOf(facilityId to Role.Manager, FacilityId(1100) to Role.Contributor)

    insertFacility(1100)
    insertAccession(facilityId = 1100)

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
}
