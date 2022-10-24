package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceNullValueTest : SearchServiceTest() {
  @Test
  fun `search leaves out null values`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1001))!!.copy(processingNotes = "Notes"))

    val fields = listOf(processingNotesField)

    val result = searchAccessions(facilityId, fields, criteria = NoConditionNode())

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG", "processingNotes" to "Notes"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do exact search for null values`() {
    insertAccession(number = "MISSING")
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1001))!!.copy(processingNotes = "Notes"))
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(processingNotes = "Other"))

    val fields = listOf(processingNotesField)
    val searchNode = FieldNode(processingNotesField, listOf("Notes", null))

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG", "processingNotes" to "Notes"),
                mapOf("id" to "1", "accessionNumber" to "MISSING"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do fuzzy search for null values`() {
    insertAccession(number = "MISSING")
    accessionsDao.update(
        accessionsDao
            .fetchOneById(AccessionId(1001))!!
            .copy(processingNotes = "some matching notes"))
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(processingNotes = "not it"))

    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(processingNotesField, listOf(null), SearchFilterType.Fuzzy)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1", "accessionNumber" to "MISSING"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }
}
