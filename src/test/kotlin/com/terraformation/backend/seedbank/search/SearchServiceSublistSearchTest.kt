package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.NotNode
import com.terraformation.backend.search.OrNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchServiceSublistSearchTest : SearchServiceTest() {
  val epochPlusOne: LocalDate = LocalDate.EPOCH.plusDays(1)
  val epochPlusTwo: LocalDate = LocalDate.EPOCH.plusDays(2)

  @BeforeEach
  fun setUp() {

    insertBag(accessionId = accessionId1, bagNumber = "101")
    insertBag(accessionId = accessionId1, bagNumber = "102")
    insertBag(accessionId = accessionId1, bagNumber = "103")
    insertBag(accessionId = accessionId2, bagNumber = "201")
    insertBag(accessionId = accessionId2, bagNumber = "202")

    val testId1 =
        insertViabilityTest(accessionId = accessionId1, notes = "this is Viability Test 1")
    val testId2 =
        insertViabilityTest(
            accessionId = accessionId2, notes = "This is Viability Test 2 extra stuff")
    val testId3 =
        insertViabilityTest(
            accessionId = accessionId1,
            notes = "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS")
    val testId4 =
        insertViabilityTest(accessionId = accessionId2, notes = "THIS IS Viability Test 4")

    insertViabilityTestResult(
        viabilityTestId = testId1, seedsGerminated = 0, recordingDate = epochPlusOne)
    insertViabilityTestResult(
        viabilityTestId = testId1, seedsGerminated = 5, recordingDate = epochPlusOne)
    insertViabilityTestResult(
        viabilityTestId = testId1, seedsGerminated = 10, recordingDate = epochPlusOne)
    insertViabilityTestResult(
        viabilityTestId = testId2, seedsGerminated = 5, recordingDate = epochPlusTwo)
    insertViabilityTestResult(
        viabilityTestId = testId2, seedsGerminated = 10, recordingDate = epochPlusTwo)
    insertViabilityTestResult(
        viabilityTestId = testId2, seedsGerminated = 15, recordingDate = epochPlusTwo)
    insertViabilityTestResult(
        viabilityTestId = testId3, seedsGerminated = 10, recordingDate = epochPlusTwo)
    insertViabilityTestResult(
        viabilityTestId = testId3, seedsGerminated = 15, recordingDate = epochPlusTwo)
    insertViabilityTestResult(
        viabilityTestId = testId3, seedsGerminated = 20, recordingDate = epochPlusTwo)
    insertViabilityTestResult(
        viabilityTestId = testId4, seedsGerminated = 30, recordingDate = epochPlusOne)
  }

  @Test
  fun `basic sublist search`() {
    val fields = listOf(bagNumberSublistField)
    val sortOrder = fields.map { SearchSortField(it) }

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
  fun `basic sublist search - NotNode`() {
    val fields = listOf(bagNumberSublistField)
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        searchAccessions(
            facilityId,
            fields,
            criteria = NoConditionNode(),
            sortOrder = sortOrder,
            sublistCriteria =
                mapOf(
                    bagsPrefix to
                        NotNode(child = FieldNode(bagNumberSublistField, listOf("101", "201")))))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "bags" to listOf(mapOf("number" to "102"), mapOf("number" to "103"))),
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "bags" to listOf(mapOf("number" to "202"))),
            ))

    assertEquals(expected, result)
  }

  @Test
  fun `basic sublist search - AndNode`() {
    val fields =
        listOf(
            rootPrefix.resolve("viabilityTests.notes"),
            rootPrefix.resolve("viabilityTests.viabilityTestResults.seedsGerminated"),
            rootPrefix.resolve("viabilityTests.viabilityTestResults.recordingDate"),
        )
    val sortOrder = fields.map { SearchSortField(it) }
    val sublistCriteria =
        mapOf(
            rootPrefix.relativeSublistPrefix("viabilityTests.viabilityTestResults")!! to
                AndNode(
                    children =
                        listOf(
                            FieldNode(
                                rootPrefix.resolve(
                                    "viabilityTests.viabilityTestResults.seedsGerminated"),
                                listOf("10")),
                            FieldNode(
                                rootPrefix.resolve(
                                    "viabilityTests.viabilityTestResults.recordingDate"),
                                listOf(epochPlusTwo.toString())),
                        )))

    val result =
        searchService.search(
            rootPrefix,
            fields,
            criteria = mapOf(rootPrefix to NoConditionNode()) + sublistCriteria,
            sortOrder = sortOrder,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "this is Viability Test 1",
                            ),
                            mapOf(
                                "notes" to
                                    "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf(
                                            "seedsGerminated" to "10",
                                            "recordingDate" to epochPlusTwo.toString(),
                                        )),
                            ),
                        )),
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "This is Viability Test 2 extra stuff",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf(
                                            "seedsGerminated" to "10",
                                            "recordingDate" to epochPlusTwo.toString(),
                                        )),
                            ),
                            mapOf(
                                "notes" to "THIS IS Viability Test 4",
                            ),
                        )),
            ))

    assertEquals(expected, result)
  }

  @Test
  fun `basic sublist search - OrNode`() {
    val fields =
        listOf(
            rootPrefix.resolve("viabilityTests.notes"),
            rootPrefix.resolve("viabilityTests.viabilityTestResults.seedsGerminated"),
            rootPrefix.resolve("viabilityTests.viabilityTestResults.recordingDate"),
        )
    val sortOrder = fields.map { SearchSortField(it) }
    val sublistCriteria =
        mapOf(
            rootPrefix.relativeSublistPrefix("viabilityTests.viabilityTestResults")!! to
                OrNode(
                    children =
                        listOf(
                            FieldNode(
                                rootPrefix.resolve(
                                    "viabilityTests.viabilityTestResults.seedsGerminated"),
                                listOf("10")),
                            FieldNode(
                                rootPrefix.resolve(
                                    "viabilityTests.viabilityTestResults.recordingDate"),
                                listOf(epochPlusOne.toString())),
                        )))

    val result =
        searchService.search(
            rootPrefix,
            fields,
            criteria = mapOf(rootPrefix to NoConditionNode()) + sublistCriteria,
            sortOrder = sortOrder,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "this is Viability Test 1",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf(
                                            "seedsGerminated" to "0",
                                            "recordingDate" to epochPlusOne.toString(),
                                        ),
                                        mapOf(
                                            "seedsGerminated" to "5",
                                            "recordingDate" to epochPlusOne.toString(),
                                        ),
                                        mapOf(
                                            "seedsGerminated" to "10",
                                            "recordingDate" to epochPlusOne.toString(),
                                        ),
                                    )),
                            mapOf(
                                "notes" to
                                    "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf(
                                            "seedsGerminated" to "10",
                                            "recordingDate" to epochPlusTwo.toString(),
                                        )),
                            ),
                        )),
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "This is Viability Test 2 extra stuff",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf(
                                            "seedsGerminated" to "10",
                                            "recordingDate" to epochPlusTwo.toString(),
                                        )),
                            ),
                            mapOf(
                                "notes" to "THIS IS Viability Test 4",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf(
                                            "seedsGerminated" to "30",
                                            "recordingDate" to epochPlusOne.toString(),
                                        )),
                            ),
                        )),
            ))

    assertEquals(expected, result)
  }

  @Test
  fun `can specify sublist criteria that is not in fields returned`() {
    val fields =
        listOf(
            rootPrefix.resolve("viabilityTests.notes"),
            rootPrefix.resolve("viabilityTests.viabilityTestResults.seedsGerminated"),
        )
    val sortOrder = fields.map { SearchSortField(it) }
    val sublistCriteria =
        mapOf(
            rootPrefix.relativeSublistPrefix("viabilityTests.viabilityTestResults")!! to
                AndNode(
                    children =
                        listOf(
                            FieldNode(
                                rootPrefix.resolve(
                                    "viabilityTests.viabilityTestResults.seedsGerminated"),
                                listOf("10")),
                            FieldNode(
                                rootPrefix.resolve(
                                    // recordingDate is not in list of fields
                                    "viabilityTests.viabilityTestResults.recordingDate"),
                                listOf(epochPlusTwo.toString())),
                        )))

    val result =
        searchService.search(
            rootPrefix,
            fields,
            criteria = mapOf(rootPrefix to NoConditionNode()) + sublistCriteria,
            sortOrder = sortOrder,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "this is Viability Test 1",
                            ),
                            mapOf(
                                "notes" to
                                    "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                        )),
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "This is Viability Test 2 extra stuff",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                            mapOf(
                                "notes" to "THIS IS Viability Test 4",
                            ),
                        )),
            ))

    assertEquals(expected, result)
  }

  @Test
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

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "this is Viability Test 1",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                            mapOf(
                                "notes" to
                                    "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                        )),
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "This is Viability Test 2 extra stuff",
                                "viabilityTestResults" to listOf(mapOf("seedsGerminated" to "10")),
                            ),
                            mapOf(
                                "notes" to "THIS IS Viability Test 4",
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
                    "notes" to "this is Viability Test 1",
                    "viabilityTestResults" to arrayListOf(mapOf("seedsGerminated" to "10")),
                ),
                mapOf(
                    "notes" to "This is Viability Test 2 extra stuff",
                    "viabilityTestResults" to arrayListOf(mapOf("seedsGerminated" to "10")),
                ),
                mapOf(
                    "notes" to "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS",
                    "viabilityTestResults" to arrayListOf(mapOf("seedsGerminated" to "10")),
                ),
                mapOf(
                    "notes" to "THIS IS Viability Test 4",
                ),
            ),
        )

    assertEquals(expected, result)
  }

  @Test
  fun `phrase match works for sublists`() {
    val fields =
        listOf(
            rootPrefix.resolve("viabilityTests.notes"),
        )
    val sortOrder = fields.map { SearchSortField(it) }
    val sublistCriteria =
        mapOf(
            rootPrefix.relativeSublistPrefix("viabilityTests")!! to
                FieldNode(
                    rootPrefix.resolve("viabilityTests.notes"),
                    listOf("extra stuff"),
                    type = SearchFilterType.PhraseMatch),
        )

    val result =
        searchService.search(
            rootPrefix,
            fields,
            criteria = mapOf(rootPrefix to NoConditionNode()) + sublistCriteria,
            sortOrder = sortOrder,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to "This is Viability Test 2 extra stuff",
                            ),
                        )),
                mapOf(
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "notes" to
                                    "this IS Viability Test 3 EXTRA STUFF AND THINGS AND WORDS",
                            ),
                        )),
            ))

    assertEquals(expected, result)
  }
}
