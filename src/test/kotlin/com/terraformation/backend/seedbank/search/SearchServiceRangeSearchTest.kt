package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SearchServiceRangeSearchTest : SearchServiceTest() {
  @Test
  fun `can do range search on integer field`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf("2", "3000"), SearchFilterType.Range)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001", "accessionNumber" to "ABCDEFG", "treesCollectedFrom" to "500")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no minimum`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf(null, "3"), SearchFilterType.Range)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(mapOf("id" to "1000", "accessionNumber" to "XYZ", "treesCollectedFrom" to "1")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no maximum`() {
    accessionsDao.update(accessionsDao.fetchOneByNumber("ABCDEFG")!!.copy(treesCollectedFrom = 500))
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf("2", null), SearchFilterType.Range)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1001", "accessionNumber" to "ABCDEFG", "treesCollectedFrom" to "500")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `range search on integer field with two nulls is rejected`() {
    val fields = listOf(treesCollectedFromField)
    val searchNode = FieldNode(treesCollectedFromField, listOf(null, null), SearchFilterType.Range)

    assertThrows<IllegalArgumentException> { searchAccessions(facilityId, fields, searchNode) }
  }
}
