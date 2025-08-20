package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NotNode
import com.terraformation.backend.search.OrNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchServiceCompoundSearchTest : SearchServiceTest() {
  private lateinit var accessionIds: List<AccessionId>

  @BeforeEach
  fun insertTreesCollectedFromExamples() {
    accessionIds =
        (10..20).map { value -> insertAccession(number = "$value", treesCollectedFrom = value) }
  }

  @Test
  fun `simple field condition`() {
    testSearch(exactly(13), listOf(13))
  }

  @Test
  fun `simple OR condition`() {
    testSearch(OrNode(listOf(exactly(13), exactly(15))), listOf(13, 15))
  }

  @Test
  fun `simple AND condition`() {
    testSearch(AndNode(listOf(between(13, 16), between(15, 18))), listOf(15, 16))
  }

  @Test
  fun `simple NOT condition`() {
    testSearch(NotNode(between(1, 18)), listOf(19, 20))
  }

  @Test
  fun `nested AND conditions inside OR condition`() {
    testSearch(
        OrNode(
            listOf(
                AndNode(listOf(between(10, 11), between(11, 12))),
                AndNode(listOf(between(13, 14), between(14, 15))),
            )
        ),
        listOf(11, 14),
    )
  }

  @Test
  fun `nested OR conditions inside AND condition`() {
    testSearch(
        AndNode(
            listOf(
                OrNode(listOf(exactly(11), exactly(13))),
                OrNode(listOf(exactly(13), exactly(15))),
            )
        ),
        listOf(13),
    )
  }

  @Test
  fun `nested AND inside OR inside AND with NOT`() {
    testSearch(
        AndNode(
            listOf(
                OrNode(
                    listOf(
                        AndNode(listOf(between(10, 14), between(11, 20))),
                        AndNode(listOf(between(18, 19), between(19, 20))),
                    )
                ),
                between(12, 20),
                NotNode(exactly(13)),
            )
        ),
        listOf(12, 14, 19),
    )
  }

  private fun exactly(value: Int) = FieldNode(plantsCollectedFromField, listOf("$value"))

  private fun between(minimum: Int?, maximum: Int?): FieldNode {
    return FieldNode(
        plantsCollectedFromField,
        listOf(minimum?.toString(), maximum?.toString()),
        type = SearchFilterType.Range,
    )
  }

  private fun testSearch(searchNode: SearchNode, expectedValues: List<Int>) {
    val expected =
        SearchResults(
            expectedValues.map { value ->
              mapOf("id" to "${accessionIds[value - 10]}", "accessionNumber" to "$value")
            }
        )
    val actual = searchAccessions(facilityId, listOf(accessionNumberField), searchNode)

    assertEquals(expected, actual)
  }
}
