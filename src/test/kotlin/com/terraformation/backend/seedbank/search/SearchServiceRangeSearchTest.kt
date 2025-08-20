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
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(treesCollectedFrom = 500))
    val fields = listOf(plantsCollectedFromField)
    val searchNode =
        FieldNode(plantsCollectedFromField, listOf("2", "3000"), SearchFilterType.Range)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "plantsCollectedFrom" to "500",
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no minimum`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(treesCollectedFrom = 500))
    val fields = listOf(plantsCollectedFromField)
    val searchNode = FieldNode(plantsCollectedFromField, listOf(null, "3"), SearchFilterType.Range)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "plantsCollectedFrom" to "1",
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can do range search on integer field with no maximum`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(treesCollectedFrom = 500))
    val fields = listOf(plantsCollectedFromField)
    val searchNode = FieldNode(plantsCollectedFromField, listOf("2", null), SearchFilterType.Range)

    val result = searchAccessions(facilityId, fields, searchNode)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "plantsCollectedFrom" to "500",
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `range search on integer field with two nulls is rejected`() {
    val fields = listOf(plantsCollectedFromField)
    val searchNode = FieldNode(plantsCollectedFromField, listOf(null, null), SearchFilterType.Range)

    assertThrows<IllegalArgumentException> { searchAccessions(facilityId, fields, searchNode) }
  }
}
