package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.default_schema.Role
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
import java.util.Locale
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

    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "B"))

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
                    "id" to "$accessionId1",
                    "bagNumber" to "A",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "bagNumber" to "B",
                    "accessionNumber" to "XYZ",
                ),
            ))

    assertEquals(expected, result)
  }

  @Test
  fun `returns multiple results for field in flattened sublist`() {
    val fields = listOf(bagNumberFlattenedField)
    val sortOrder = fields.map { SearchSortField(it) }

    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "A"))
    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "B"))

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "bags_number" to "A",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "bags_number" to "B",
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                ),
            ))

    assertEquals(expected, result)
  }

  @Test
  fun `honors sort order`() {
    val fields =
        listOf(speciesNameField, accessionNumberField, plantsCollectedFromField, activeField)
    val sortOrder = fields.map { SearchSortField(it, SearchDirection.Descending) }

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "speciesName" to "Other Dogwood",
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "plantsCollectedFrom" to "2",
                    "active" to "Active",
                ),
                mapOf(
                    "speciesName" to "Kousa Dogwood",
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "plantsCollectedFrom" to "1",
                    "active" to "Active",
                ),
            ))

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
            ))

    val swedishExpected =
        SearchResults(
            listOf(
                // Swedish ICU collation puts Å after Z
                mapOf("scientificName" to "Kousa Dogwood"),
                mapOf("scientificName" to "Other Dogwood"),
                mapOf("scientificName" to "Å x"),
            ))

    val englishResult =
        Locale.ENGLISH.use {
          searchService.search(
              prefix, listOf(field), mapOf(prefix to NoConditionNode()), listOf(sortOrder))
        }
    val swedishResult =
        Locale.forLanguageTag("se").use {
          searchService.search(
              prefix, listOf(field), mapOf(prefix to NoConditionNode()), listOf(sortOrder))
        }

    assertEquals(englishExpected, englishResult, "English should put Å before K")
    assertEquals(swedishExpected, swedishResult, "Swedish should put Å after O")
  }

  @Test
  fun `can filter on computed fields whose raw values are being queried`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(stateId = AccessionState.UsedUp))

    val fields = listOf(accessionNumberField, stateField)
    val searchNode = FieldNode(activeField, listOf("Inactive"))

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ", "state" to "Used Up")))

    assertEquals(expected, result)
  }

  @Test
  fun `exact search on text fields is case- and accent-insensitive`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId2)!!.copy(processingNotes = "Sómé Mätching Nótes"))

    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(processingNotesField, listOf("some matching Notés"), SearchFilterType.Exact)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG")))

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
              countryPrefix,
              listOf(countryCodeField, countryNameField),
              mapOf(countryPrefix to searchNode))
        }

    val expected = SearchResults(listOf(mapOf("code" to "US", "name" to gibberishValue)))

    assertEquals(expected, result)
  }

  @Test
  fun `exact search on localizable text fields is accent-insensitive`() {
    val searchNode = FieldNode(countryNameField, listOf("cote d’ivoire"), SearchFilterType.Exact)

    val result =
        searchService.search(
            countryPrefix, listOf(countryNameField), mapOf(countryPrefix to searchNode))

    val expected = SearchResults(listOf(mapOf("name" to "Côte d’Ivoire")))

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
              countryPrefix,
              listOf(countryNameField),
              mapOf(countryPrefix to searchNode),
              listOf(sortField))
        }

    val expected =
        SearchResults(
            listOf(
                mapOf("name" to unitedStates),
                mapOf("name" to togo),
            ))

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
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "species_checkedTime" to checkedTimeString)))

    assertEquals(expected, result)
  }

  @Test
  fun `search only includes results from requested facility`() {
    val facilityId = inserted.facilityId

    val otherFacilityId = insertFacility()
    insertAccession(facilityId = otherFacilityId)

    every { user.facilityRoles } returns
        mapOf(facilityId to Role.Manager, otherFacilityId to Role.Contributor)

    val fields = listOf(accessionNumberField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG"),
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
            ))

    val actual =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    assertEquals(expected, actual)
  }
}
