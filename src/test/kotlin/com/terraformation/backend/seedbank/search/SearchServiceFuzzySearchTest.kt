package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceFuzzySearchTest : SearchServiceTest() {
  @Test
  fun `fuzzy search on text fields is case- and accent-insensitive`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId2)!!.copy(processingNotes = "Some Mátching Notes")
    )
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(processingNotes = "Not It")
    )

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(processingNotesField, listOf("mãtç"), SearchFilterType.Fuzzy)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG")))

    assertEquals(expected, result)
  }

  @Test
  fun `fuzzy search on text fields handles single-character search values`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId2)!!.copy(processingNotes = "Some Matching Notes")
    )
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(processingNotes = "Not It")
    )

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(processingNotesField, listOf("G"), SearchFilterType.Fuzzy)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG")))

    assertEquals(expected, result)
  }

  @Test
  fun `exact-or-fuzzy search on text fields limits results to exact matches if any exist`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(number = "22-1-100"))
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(number = "22-1-101"))

    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(accessionNumberField, listOf("22-1-100"), SearchFilterType.ExactOrFuzzy)

    assertEquals(
        SearchResults(listOf(mapOf("id" to "$accessionId1", "accessionNumber" to "22-1-100"))),
        searchAccessions(facilityId, fields, searchNode),
        "Search for value with an exact match",
    )

    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(number = "22-1-102"))

    assertEquals(
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId2", "accessionNumber" to "22-1-101"),
                mapOf("id" to "$accessionId1", "accessionNumber" to "22-1-102"),
            )
        ),
        searchAccessions(facilityId, fields, searchNode),
        "Search for value without an exact match",
    )
  }

  @Test
  fun `fuzzy search on text fields does not limit results to exact matches`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(number = "22-1-10"))
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(number = "22-1-100"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(accessionNumberField, listOf("22-1-10"), SearchFilterType.Fuzzy)

    assertEquals(
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId1", "accessionNumber" to "22-1-10"),
                mapOf("id" to "$accessionId2", "accessionNumber" to "22-1-100"),
            )
        ),
        searchAccessions(facilityId, fields, searchNode),
    )
  }

  @Test
  fun `exact-or-fuzzy search for null and non-null values matches non-null exact values if any exist`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(processingNotes = "Notes"))

    val fields = listOf(processingNotesField)
    val searchNode =
        FieldNode(processingNotesField, listOf("Notes", null), SearchFilterType.ExactOrFuzzy)

    assertEquals(
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "processingNotes" to "Notes",
                )
            )
        ),
        searchAccessions(facilityId, fields, searchNode),
    )
  }
}
