package com.terraformation.backend.seedbank.search

import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceBasicSearchTest : SearchServiceTest() {
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
  fun `can filter on computed fields whose raw values are being queried`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(stateId = AccessionState.Withdrawn))

    val fields = listOf(accessionNumberField, stateField)
    val searchNode = FieldNode(activeField, listOf("Inactive"))

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("id" to "1000", "accessionNumber" to "XYZ", "state" to "Withdrawn")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `exact search on text fields is case-insensitive`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1001))!!.copy(storageNotes = "Some Matching Notes"))

    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(storageNotesField, listOf("some matching Notes"), SearchFilterType.Exact)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can search for timestamps using different but equivalent RFC 3339 time format`() {
    val fields = listOf(checkedInTimeField)
    val searchNode =
        FieldNode(
            checkedInTimeField,
            listOf(checkedInTimeString.replace("Z", ".000+00:00")),
            SearchFilterType.Exact)

    val result = searchAccessions(facilityId, fields, searchNode)

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
  fun `search only includes results from requested facility`() {
    every { user.facilityRoles } returns
        mapOf(facilityId to Role.MANAGER, FacilityId(1100) to Role.CONTRIBUTOR)

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
