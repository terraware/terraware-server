package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceFuzzySearchTest : SearchServiceTest() {
  @Test
  fun `fuzzy search on text fields is case-insensitive`() {
    accessionsDao.update(
        accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(storageNotes = "Some Matching Notes"))
    accessionsDao.update(accessionsDao.fetchOneByNumber("XYZ")!!.copy(storageNotes = "Not It"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(storageNotesField, listOf("matc"), SearchFilterType.Fuzzy)

    val result = searchAccessions(facilityId, fields, searchNode)

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

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(listOf(mapOf("id" to "1001", "accessionNumber" to "ABCDEFG")), cursor = null)

    assertEquals(expected, result)
  }
}
