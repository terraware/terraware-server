package com.terraformation.backend.search

import com.terraformation.backend.TestClock
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TEST_RESULTS
import com.terraformation.backend.search.table.SearchTables
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchFieldPrefixTest {
  private val clock = TestClock()
  private val tables = SearchTables(clock)

  private val accessionsField =
      SublistField(
          name = "accessions",
          searchTable = tables.accessions,
          isMultiValue = true,
          conditionForMultiset = PROJECTS.ID.eq(ACCESSIONS.PROJECT_ID),
      )
  private val viabilityTestsField =
      SublistField(
          name = "viabilityTests",
          searchTable = tables.viabilityTests,
          isMultiValue = true,
          conditionForMultiset = ACCESSIONS.ID.eq(VIABILITY_TESTS.ACCESSION_ID),
      )
  private val viabilityTestsResultsField =
      SublistField(
          name = "viabilityTestResults",
          searchTable = tables.viabilityTestResults,
          isMultiValue = true,
          conditionForMultiset = VIABILITY_TESTS.ID.eq(VIABILITY_TEST_RESULTS.TEST_ID),
      )

  @Test
  fun `relativeSublistPrefix non-existent`() {
    assertNull(
        SearchFieldPrefix(root = tables.accessions).relativeSublistPrefix("someBadPath"),
        "Relative sublist prefix should be null for non existent sublist",
    )
  }

  @Test
  fun `relativeSublistPrefix for sublist one level deep`() {
    val expected =
        SearchFieldPrefix(root = tables.accessions, sublists = listOf(viabilityTestsField))
    val actual = SearchFieldPrefix(root = tables.accessions).relativeSublistPrefix("viabilityTests")
    assertEquals(expected, actual, "Relative sublist prefix should work for one level deep")
  }

  @Test
  fun `relativeSublistPrefix for sublist two levels deep`() {
    val expected =
        SearchFieldPrefix(
            root = tables.accessions,
            sublists = listOf(viabilityTestsField, viabilityTestsResultsField),
        )
    val actual =
        SearchFieldPrefix(root = tables.accessions)
            .relativeSublistPrefix("viabilityTests.viabilityTestResults")
    assertEquals(expected, actual, "Relative sublist prefix should work for two levels deep")
  }

  @Test
  fun `relativeSublistPrefix for sublist three levels deep`() {
    val expected =
        SearchFieldPrefix(
            root = tables.projects,
            sublists = listOf(accessionsField, viabilityTestsField, viabilityTestsResultsField),
        )
    val actual =
        SearchFieldPrefix(root = tables.projects)
            .relativeSublistPrefix("accessions.viabilityTests.viabilityTestResults")
    assertEquals(expected, actual, "Relative sublist prefix should work for three levels deep")
  }
}
