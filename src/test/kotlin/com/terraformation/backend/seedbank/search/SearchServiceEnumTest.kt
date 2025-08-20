package com.terraformation.backend.seedbank.search

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
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceEnumTest : SearchServiceTest() {
  @Test
  fun `sorts enum fields by display name rather than ID`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId2)!!.copy(stateId = AccessionState.Drying)
    )
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(stateId = AccessionState.Processing)
    )

    val fields = listOf(stateField)
    val sortOrder = listOf(SearchSortField(stateField, SearchDirection.Descending))

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ", "state" to "Processing"),
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG", "state" to "Drying"),
            )
        )

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
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ", "state" to "In Storage")
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can search on enum in child table`() {
    viabilityTestsDao.insert(
        ViabilityTestsRow(accessionId = accessionId1, testType = ViabilityTestType.Lab),
        ViabilityTestsRow(accessionId = accessionId1, testType = ViabilityTestType.Nursery),
        ViabilityTestsRow(accessionId = accessionId2, testType = ViabilityTestType.Lab),
    )

    val fields = listOf(viabilityTestsTypeField)
    val sortOrder =
        listOf(SearchSortField(accessionNumberField), SearchSortField(viabilityTestsTypeField))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "viabilityTests_type" to "Lab",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests_type" to "Lab",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests_type" to "Nursery",
                ),
            )
        )

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
        accessionsDao.fetchOneById(accessionId2)!!.copy(stateId = AccessionState.Drying)
    )
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(stateId = AccessionState.AwaitingProcessing)
    )

    val fields = listOf(stateField)
    val sortOrder = listOf(SearchSortField(stateField))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "state" to gibberishDrying,
                    "accessionNumber" to "ABCDEFG",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "state" to gibberishAwaitingProcessing,
                    "accessionNumber" to "XYZ",
                ),
            )
        )

    val actual =
        Locales.GIBBERISH.use {
          searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)
        }

    assertEquals(expected, actual)
  }

  @Test
  fun `matches localized display name`() {
    val gibberishActive = AccessionActive.Active.toString().toGibberish()
    val gibberishInStorage = AccessionState.InStorage.getDisplayName(Locale.ENGLISH).toGibberish()
    val fields = listOf(activeField, stateField)
    val search =
        AndNode(
            listOf(
                FieldNode(activeField, listOf(gibberishActive)),
                FieldNode(stateField, listOf(gibberishInStorage)),
            )
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "accessionNumber" to "XYZ",
                    "active" to gibberishActive,
                    "id" to "$accessionId1",
                    "state" to gibberishInStorage,
                )
            )
        )

    val actual = Locales.GIBBERISH.use { searchAccessions(facilityId, fields, criteria = search) }

    assertEquals(expected, actual)
  }
}
