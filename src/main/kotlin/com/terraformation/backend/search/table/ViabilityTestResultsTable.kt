package com.terraformation.backend.search.table

import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TEST_RESULTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ViabilityTestResultsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = VIABILITY_TEST_RESULTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          viabilityTests.asSingleValueSublist(
              "viabilityTest",
              VIABILITY_TEST_RESULTS.TEST_ID.eq(VIABILITY_TESTS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("recordingDate", VIABILITY_TEST_RESULTS.RECORDING_DATE),
          integerField("seedsGerminated", VIABILITY_TEST_RESULTS.SEEDS_GERMINATED),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.viabilityTests

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(VIABILITY_TESTS).on(VIABILITY_TEST_RESULTS.TEST_ID.eq(VIABILITY_TESTS.ID))
  }
}
