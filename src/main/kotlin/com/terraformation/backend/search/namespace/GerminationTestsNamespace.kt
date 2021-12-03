package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.search.SearchTables

class GerminationTestsNamespace(
    searchTables: SearchTables,
    accessionsNamespace: AccessionsNamespace
) : SearchFieldNamespace() {
  private val germinationsNamespace = GerminationsNamespace(searchTables, this)

  override val sublists: List<SublistField> =
      listOf(
          accessionsNamespace.asSingleValueSublist(
              "accession", GERMINATION_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID)),
          germinationsNamespace.asMultiValueSublist(
              "germinations", GERMINATION_TESTS.ID.eq(GERMINATIONS.TEST_ID)))

  override val fields: List<SearchField> =
      with(searchTables) {
        listOf(
            germinationTests.dateField(
                "endDate", "Germination end date", GERMINATION_TESTS.END_DATE),
            germinationTests.textField(
                "notes", "Notes (germination test)", GERMINATION_TESTS.NOTES),
            germinationTests.integerField(
                "percentGerminated", "% Viability", GERMINATION_TESTS.TOTAL_PERCENT_GERMINATED),
            germinationTests.enumField("seedType", "Seed type", GERMINATION_TESTS.SEED_TYPE_ID),
            germinationTests.integerField(
                "seedsSown", "Number of seeds sown", GERMINATION_TESTS.SEEDS_SOWN),
            germinationTests.dateField(
                "startDate", "Germination start date", GERMINATION_TESTS.START_DATE),
            germinationTests.enumField(
                "substrate", "Germination substrate", GERMINATION_TESTS.SUBSTRATE_ID),
            germinationTests.enumField(
                "treatment", "Germination treatment", GERMINATION_TESTS.TREATMENT_ID),
            germinationTests.enumField(
                "type", "Germination test type", GERMINATION_TESTS.TEST_TYPE),
        )
      }
}
