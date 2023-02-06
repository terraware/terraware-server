package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceFuzzySearchTest : SearchServiceTest() {
  @Test
  fun `fuzzy search on text fields is case- and accent-insensitive`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneById(AccessionId(1001))!!
            .copy(processingNotes = "Some Mátching Notes"))
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(processingNotes = "Not It"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(processingNotesField, listOf("mãtç"), SearchFilterType.Fuzzy)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `fuzzy search on text fields handles single-character search values`() {
    accessionsDao.update(
        accessionsDao
            .fetchOneById(AccessionId(1001))!!
            .copy(processingNotes = "Some Matching Notes"))
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(processingNotes = "Not It"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(processingNotesField, listOf("G"), SearchFilterType.Fuzzy)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `fuzzy search on text fields limits results to exact matches if any exist`() {
    accessionsDao.update(accessionsDao.fetchOneById(AccessionId(1000))!!.copy(number = "22-1-100"))
    accessionsDao.update(accessionsDao.fetchOneById(AccessionId(1001))!!.copy(number = "22-1-101"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(accessionNumberField, listOf("22-1-100"), SearchFilterType.Fuzzy)

    assertEquals(
        SearchResults(
            listOf(mapOf("id" to "1000", "accessionNumber" to "22-1-100")), cursor = null),
        searchAccessions(facilityId, fields, searchNode),
        "Search for value with an exact match")

    accessionsDao.update(accessionsDao.fetchOneById(AccessionId(1000))!!.copy(number = "22-1-102"))

    assertEquals(
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "22-1-101"),
                mapOf("id" to "1000", "accessionNumber" to "22-1-102"),
            ),
            null),
        searchAccessions(facilityId, fields, searchNode),
        "Search for value without an exact match")
  }

  @Test
  fun `fuzzy search for null and non-null values matches non-null exact values if any exist`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1001))!!.copy(processingNotes = "Notes"))

    val fields = listOf(processingNotesField)
    val searchNode = FieldNode(processingNotesField, listOf("Notes", null), SearchFilterType.Fuzzy)

    assertEquals(
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG", "processingNotes" to "Notes"),
            ),
            null),
        searchAccessions(facilityId, fields, searchNode))
  }
}
