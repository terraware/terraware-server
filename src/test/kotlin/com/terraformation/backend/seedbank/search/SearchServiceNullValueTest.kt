package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceNullValueTest : SearchServiceTest() {
  @Test
  fun `search leaves out null values`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(processingNotes = "Notes"))

    val fields = listOf(processingNotesField)

    val result = searchAccessions(facilityId, fields, criteria = NoConditionNode())

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "processingNotes" to "Notes",
                ),
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can do exact search for null values`() {
    val missingAccessionId = insertAccession(number = "MISSING")
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(processingNotes = "Notes"))
    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(processingNotes = "Other"))

    val fields = listOf(processingNotesField)
    val searchNode = FieldNode(processingNotesField, listOf("Notes", null))

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "processingNotes" to "Notes",
                ),
                mapOf("id" to "$missingAccessionId", "accessionNumber" to "MISSING"),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `fuzzy search treats null values as no-ops`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(processingNotes = "Notes"))

    val fields = listOf(processingNotesField)
    val searchNode =
        FieldNode(processingNotesField, listOf("non-matching value", null), SearchFilterType.Fuzzy)

    assertEquals(
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "processingNotes" to "Notes",
                ),
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
            )
        ),
        searchAccessions(facilityId, fields, searchNode),
    )
  }
}
