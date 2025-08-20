package com.terraformation.backend.search.table

import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TEST_RESULTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ViabilityTestsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = VIABILITY_TESTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession",
              VIABILITY_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID),
          ),
          viabilityTestResults.asMultiValueSublist(
              "viabilityTestResults",
              VIABILITY_TESTS.ID.eq(VIABILITY_TEST_RESULTS.TEST_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("endDate", VIABILITY_TESTS.END_DATE),
          idWrapperField("id", VIABILITY_TESTS.ID) { ViabilityTestId(it) },
          textField("notes", VIABILITY_TESTS.NOTES),
          integerField("seedsCompromised", VIABILITY_TESTS.SEEDS_COMPROMISED),
          integerField("seedsEmpty", VIABILITY_TESTS.SEEDS_EMPTY),
          integerField("seedsFilled", VIABILITY_TESTS.SEEDS_FILLED),
          integerField("seedsTested", VIABILITY_TESTS.SEEDS_SOWN),
          enumField("seedType", VIABILITY_TESTS.SEED_TYPE_ID),
          dateField("startDate", VIABILITY_TESTS.START_DATE),
          enumField("substrate", VIABILITY_TESTS.SUBSTRATE_ID),
          enumField("treatment", VIABILITY_TESTS.TREATMENT_ID),
          enumField("type", VIABILITY_TESTS.TEST_TYPE),
          integerField("viabilityPercent", VIABILITY_TESTS.TOTAL_PERCENT_GERMINATED),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(VIABILITY_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
