package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

internal class SearchServiceSublistSearchTest : SearchServiceTest() {
  @BeforeEach
  fun setUp() {
    val testId1 = insertViabilityTest(accessionId = accessionId1, notes = "test1")
    val testId2 = insertViabilityTest(accessionId = accessionId2, notes = "test2")
    val testId3 = insertViabilityTest(accessionId = accessionId1, notes = "test3")
    val testId4 = insertViabilityTest(accessionId = accessionId2, notes = "test4")

    insertViabilityTestResult(viabilityTestId = testId1, seedsGerminated = 0)
    insertViabilityTestResult(viabilityTestId = testId1, seedsGerminated = 5)
    insertViabilityTestResult(viabilityTestId = testId1, seedsGerminated = 10)
    insertViabilityTestResult(viabilityTestId = testId2, seedsGerminated = 5)
    insertViabilityTestResult(viabilityTestId = testId2, seedsGerminated = 10)
    insertViabilityTestResult(viabilityTestId = testId2, seedsGerminated = 15)
    insertViabilityTestResult(viabilityTestId = testId3, seedsGerminated = 10)
    insertViabilityTestResult(viabilityTestId = testId3, seedsGerminated = 15)
    insertViabilityTestResult(viabilityTestId = testId3, seedsGerminated = 20)
    insertViabilityTestResult(viabilityTestId = testId4, seedsGerminated = 30)
  }

  @Test
  fun `basic sublist search`() {
    val fields = listOf(bagNumberSublistField)
    val sortOrder = fields.map { SearchSortField(it) }

    insertBag(accessionId = accessionId1, bagNumber = "101")
    insertBag(accessionId = accessionId1, bagNumber = "102")
    insertBag(accessionId = accessionId1, bagNumber = "103")
    insertBag(accessionId = accessionId2, bagNumber = "201")
    insertBag(accessionId = accessionId2, bagNumber = "202")

    val result =
        searchAccessions(
            facilityId,
            fields,
            criteria = NoConditionNode(),
            sortOrder = sortOrder,
            sublistCriteria =
                mapOf(bagsPrefix to FieldNode(bagNumberSublistField, listOf("101", "201"))))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "bags" to listOf(mapOf("number" to "101"))),
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "bags" to listOf(mapOf("number" to "201"))),
            ))

    assertEquals(expected, result)
  }

  @Test
  @Disabled("prefixes in sublistCriteria that are deeper than 1 aren't going all the way down")
  fun `simple prefix, deep selector`() {
    val fields =
        listOf(
            rootPrefix.resolve("viabilityTests.notes"),
            rootPrefix.resolve("viabilityTests.viabilityTestResults.seedsGerminated"),
        )
    val sortOrder = fields.map { SearchSortField(it) }
    val sublistCriteria =
        mapOf(
            rootPrefix.relativeSublistPrefix("viabilityTests.viabilityTestResults")!! to
                FieldNode(
                    rootPrefix.resolve("viabilityTests.viabilityTestResults.seedsGerminated"),
                    listOf("10")))

    val result =
        searchService.search(
            rootPrefix,
            fields,
            criteria = mapOf(rootPrefix to NoConditionNode()) + sublistCriteria,
            sortOrder = sortOrder,
        )

    // todo this fails because prefixes in sublistCriteria that are deeper than 1 aren't
    //   going all the way down
    val expected =
        SearchResults(
            listOf(
                mapOf(
                    //                    "id" to "$accessionId1",
                    //                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "test1",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                            mapOf(
                                "notes" to "test3",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                            //                        )),
                            //                mapOf(
                            //                    "id" to "$accessionId2",
                            //                    "accessionNumber" to "ABCDEFG",
                            //                    "viabilityTests" to
                            //                        listOf(
                            mapOf(
                                "notes" to "test2",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                            mapOf(
                                "notes" to "test4",
                            ),
                        )),
            ))

    assertEquals(expected, result)
  }

  @Test
  fun `deeper prefix, simple selector`() {
    val prefix = SearchFieldPrefix(root = tables.viabilityTests)
    val fields =
        listOf(
            prefix.resolve("notes"),
            prefix.resolve("viabilityTestResults.seedsGerminated"),
        )
    val sortOrder = fields.map { SearchSortField(it) }

    val sublistCriteria =
        mapOf(
            prefix.relativeSublistPrefix("viabilityTestResults")!! to
                FieldNode(prefix.resolve("viabilityTestResults.seedsGerminated"), listOf("10")))

    val result =
        searchService.search(
            prefix,
            fields,
            mapOf(
                prefix to
                    FieldNode(prefix.resolve("accession.facility.id"), listOf("$facilityId"))) +
                sublistCriteria,
            sortOrder,
        )

    val expected =
        SearchResults(
            arrayListOf(
                mapOf(
                    "notes" to "test1",
                    "viabilityTestResults" to arrayListOf(mapOf("seedsGerminated" to "10")),
                ),
                mapOf(
                    "notes" to "test2",
                    "viabilityTestResults" to arrayListOf(mapOf("seedsGerminated" to "10")),
                ),
                mapOf(
                    "notes" to "test3",
                    "viabilityTestResults" to arrayListOf(mapOf("seedsGerminated" to "10")),
                ),
                mapOf(
                    "notes" to "test4",
                ),
            ),
        )

    assertEquals(expected, result)
  }
}
