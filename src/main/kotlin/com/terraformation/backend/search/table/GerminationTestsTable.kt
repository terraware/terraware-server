package com.terraformation.backend.search.table

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class GerminationTestsTable(
    private val tables: SearchTables,
    fuzzySearchOperators: FuzzySearchOperators
) : SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = GERMINATION_TESTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession", GERMINATION_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID)),
          germinations.asMultiValueSublist(
              "germinations", GERMINATION_TESTS.ID.eq(GERMINATIONS.TEST_ID)))
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("endDate", "Germination end date", GERMINATION_TESTS.END_DATE),
          textField("notes", "Notes (germination test)", GERMINATION_TESTS.NOTES),
          integerField(
              "percentGerminated", "% Viability", GERMINATION_TESTS.TOTAL_PERCENT_GERMINATED),
          enumField("seedType", "Seed type", GERMINATION_TESTS.SEED_TYPE_ID),
          integerField("seedsSown", "Number of seeds sown", GERMINATION_TESTS.SEEDS_SOWN),
          dateField("startDate", "Germination start date", GERMINATION_TESTS.START_DATE),
          enumField("substrate", "Germination substrate", GERMINATION_TESTS.SUBSTRATE_ID),
          enumField("treatment", "Germination treatment", GERMINATION_TESTS.TREATMENT_ID),
          enumField("type", "Germination test type", GERMINATION_TESTS.TEST_TYPE),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(GERMINATION_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
