package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchServiceCountTest : SearchServiceTest() {
  val epochPlusOne: LocalDate = LocalDate.EPOCH.plusDays(1)
  val epochPlusTwo: LocalDate = LocalDate.EPOCH.plusDays(2)

  private lateinit var testId1: ViabilityTestId
  private lateinit var testId2: ViabilityTestId
  private lateinit var testId3: ViabilityTestId
  private lateinit var testId4: ViabilityTestId

  @BeforeEach
  fun setUp() {
    insertBag(accessionId = accessionId1, bagNumber = "101")
    insertBag(accessionId = accessionId1, bagNumber = "102")
    insertBag(accessionId = accessionId1, bagNumber = "103")
    insertBag(accessionId = accessionId2, bagNumber = "201")
    insertBag(accessionId = accessionId2, bagNumber = "202")

    testId1 = insertViabilityTest(accessionId = accessionId1, notes = "this is Viability Test 1")
    testId2 =
        insertViabilityTest(
            accessionId = accessionId2,
            notes = "This is Viability Test 2 extra stuff",
        )
    testId3 =
        insertViabilityTest(
            accessionId = accessionId1,
            notes = "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS",
        )
    testId4 = insertViabilityTest(accessionId = accessionId2, notes = "THIS IS Viability Test 4")
  }

  @Test
  fun `basic count`() {
    assertEquals(
        2,
        searchService.searchCount(rootPrefix, mapOf(rootPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `respects criteria`() {
    val criteria = FieldNode(bagNumberSublistField, listOf("101"))

    assertEquals(1, searchService.searchCount(rootPrefix, mapOf(rootPrefix to criteria)))
  }

  @Test
  fun `sublist criteria doesn't affect count`() {
    val prefix = SearchFieldPrefix(root = tables.viabilityTests)
    val germinatedPrefix = prefix.relativeSublistPrefix("viabilityTestResults")!!
    val sublistCriteria =
        FieldNode(
            prefix.resolve("viabilityTestResults.seedsGerminated"),
            listOf("1", "5"),
            type = SearchFilterType.Range,
        )

    insertTestResults(testId1, 10)
    insertTestResults(testId2, 10)

    assertEquals(
        4,
        searchService.searchCount(
            prefix,
            mapOf(prefix to NoConditionNode(), germinatedPrefix to sublistCriteria),
        ),
    )
  }

  private fun insertTestResults(testId: ViabilityTestId, total: Int) {
    for (n in 1..total) {
      insertViabilityTestResult(
          viabilityTestId = testId,
          seedsGerminated = n,
          recordingDate = epochPlusOne,
      )
    }
  }
}
