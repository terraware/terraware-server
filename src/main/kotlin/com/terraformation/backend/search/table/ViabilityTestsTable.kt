package com.terraformation.backend.search.table

import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tables.references.VIABILITY_TEST_RESULTS
import com.terraformation.backend.db.tables.references.VIABILITY_TEST_SELECTIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class ViabilityTestsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = VIABILITY_TESTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession", VIABILITY_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID)),
          viabilityTestResults.asMultiValueSublist(
              "viabilityTestResults", VIABILITY_TESTS.ID.eq(VIABILITY_TEST_RESULTS.TEST_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("endDate", "Viability test end date", VIABILITY_TESTS.END_DATE),
          idWrapperField("id", "Viability test ID", VIABILITY_TESTS.ID) { ViabilityTestId(it) },
          textField("notes", "Notes (viability test)", VIABILITY_TESTS.NOTES),
          integerField(
              "percentGerminated", "% Viability", VIABILITY_TESTS.TOTAL_PERCENT_GERMINATED),
          enumField("seedType", "Seed type", VIABILITY_TESTS.SEED_TYPE_ID),
          integerField("seedsSown", "Number of seeds sown", VIABILITY_TESTS.SEEDS_SOWN),
          existsField(
              "selected",
              "Viability test selected",
              VIABILITY_TESTS.ID,
              DSL.selectOne()
                  .from(VIABILITY_TEST_SELECTIONS)
                  .where(VIABILITY_TEST_SELECTIONS.VIABILITY_TEST_ID.eq(VIABILITY_TESTS.ID))),
          dateField("startDate", "Viability test start date", VIABILITY_TESTS.START_DATE),
          enumField("substrate", "Germination substrate", VIABILITY_TESTS.SUBSTRATE_ID),
          enumField("treatment", "Germination treatment", VIABILITY_TESTS.TREATMENT_ID),
          enumField("type", "Viability test type", VIABILITY_TESTS.TEST_TYPE),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(VIABILITY_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
