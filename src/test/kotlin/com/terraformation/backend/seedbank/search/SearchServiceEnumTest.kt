package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.seedbank.model.AccessionActive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceEnumTest : SearchServiceTest() {
  @Test
  fun `sorts enum fields by display name rather than ID`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1001))!!.copy(stateId = AccessionState.Drying))
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(stateId = AccessionState.Processing))

    val fields = listOf(stateField)
    val sortOrder = listOf(SearchSortField(stateField, SearchDirection.Descending))

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1000", "accessionNumber" to "XYZ", "state" to "Processing"),
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG", "state" to "Drying"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `uses enum display name when it differs from Kotlin name`() {
    val searchNode = FieldNode(stateField, listOf("In Storage"))
    val fields = listOf(stateField)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1000", "accessionNumber" to "XYZ", "state" to "In Storage"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can search on enum in child table`() {
    viabilityTestsDao.insert(
        ViabilityTestsRow(accessionId = AccessionId(1000), testType = ViabilityTestType.Lab),
        ViabilityTestsRow(accessionId = AccessionId(1000), testType = ViabilityTestType.Nursery),
        ViabilityTestsRow(accessionId = AccessionId(1001), testType = ViabilityTestType.Lab),
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
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)
    assertEquals(expected, actual)
  }

  @Test
  fun `sorts enum fields by localized display name`() {
    // "Awaiting Processing" comes after "Drying" in gibberish because the words are swapped
    val gibberishDrying = "RHJ5aW5n"
    val gibberishAwaitingProcessing = "UHJvY2Vzc2luZw QXdhaXRpbmc"

    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1001))!!.copy(stateId = AccessionState.Drying))
    accessionsDao.update(
        accessionsDao
            .fetchOneById(AccessionId(1000))!!
            .copy(stateId = AccessionState.AwaitingProcessing))

    val fields = listOf(stateField)
    val sortOrder = listOf(SearchSortField(stateField))

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "state" to gibberishDrying, "accessionNumber" to "ABCDEFG"),
                mapOf(
                    "id" to "1000",
                    "state" to gibberishAwaitingProcessing,
                    "accessionNumber" to "XYZ"),
            ),
            cursor = null)

    val actual =
        Locales.GIBBERISH.use {
          searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)
        }

    assertEquals(expected, actual)
  }

  @Test
  fun `matches localized display name`() {
    val gibberishActive = AccessionActive.Active.toString().toGibberish()
    val gibberishInStorage = AccessionState.InStorage.displayName.toGibberish()
    val fields = listOf(activeField, stateField)
    val search =
        AndNode(
            listOf(
                FieldNode(activeField, listOf(gibberishActive)),
                FieldNode(stateField, listOf(gibberishInStorage))))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "accessionNumber" to "XYZ",
                    "active" to gibberishActive,
                    "id" to "1000",
                    "state" to gibberishInStorage,
                )),
            cursor = null)

    val actual = Locales.GIBBERISH.use { searchAccessions(facilityId, fields, criteria = search) }

    assertEquals(expected, actual)
  }
}
