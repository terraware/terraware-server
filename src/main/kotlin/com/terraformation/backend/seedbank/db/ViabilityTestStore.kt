package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tables.references.VIABILITY_TEST_RESULTS
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@ManagedBean
class ViabilityTestStore(private val dslContext: DSLContext) {
  fun viabilityTestsMultiset(
      idField: Field<AccessionId?> = ACCESSIONS.ID
  ): Field<List<ViabilityTestModel>> {
    val viabilityTestResultsMultiset = viabilityTestResultsMultiset()

    return with(VIABILITY_TESTS) {
      DSL.multiset(
              DSL.select(VIABILITY_TESTS.asterisk(), viabilityTestResultsMultiset)
                  .from(VIABILITY_TESTS)
                  .where(ACCESSION_ID.eq(idField))
                  .orderBy(ID))
          .convertFrom { result ->
            result.map { record ->
              ViabilityTestModel(
                  record[ID]!!,
                  record[ACCESSION_ID]!!,
                  record[TEST_TYPE]!!,
                  record[START_DATE],
                  record[END_DATE],
                  record[SEED_TYPE_ID],
                  record[SUBSTRATE_ID],
                  record[TREATMENT_ID],
                  record[SEEDS_SOWN],
                  record[TOTAL_PERCENT_GERMINATED],
                  record[TOTAL_SEEDS_GERMINATED],
                  record[NOTES],
                  record[STAFF_RESPONSIBLE],
                  record[viabilityTestResultsMultiset]?.ifEmpty { null },
                  SeedQuantityModel.of(record[REMAINING_QUANTITY], record[REMAINING_UNITS_ID]),
              )
            }
          }
    }
  }

  private fun viabilityTestResultsMultiset(): Field<List<ViabilityTestResultModel>> {
    return DSL.multiset(
            DSL.select(
                    VIABILITY_TEST_RESULTS.ID,
                    VIABILITY_TEST_RESULTS.TEST_ID,
                    VIABILITY_TEST_RESULTS.RECORDING_DATE,
                    VIABILITY_TEST_RESULTS.SEEDS_GERMINATED,
                )
                .from(VIABILITY_TEST_RESULTS)
                .where(VIABILITY_TEST_RESULTS.TEST_ID.eq(VIABILITY_TESTS.ID))
                .orderBy(
                    VIABILITY_TEST_RESULTS.RECORDING_DATE.desc(), VIABILITY_TEST_RESULTS.ID.desc()),
        )
        .convertFrom { result ->
          result.map { record ->
            ViabilityTestResultModel(
                record[VIABILITY_TEST_RESULTS.ID]!!,
                record[VIABILITY_TEST_RESULTS.TEST_ID]!!,
                record[VIABILITY_TEST_RESULTS.RECORDING_DATE]!!,
                record[VIABILITY_TEST_RESULTS.SEEDS_GERMINATED]!!,
            )
          }
        }
  }

  fun insertViabilityTest(
      accessionId: AccessionId,
      viabilityTest: ViabilityTestModel
  ): ViabilityTestModel {
    val calculatedTest: ViabilityTestModel = viabilityTest.withCalculatedValues()

    val testId =
        with(VIABILITY_TESTS) {
          dslContext
              .insertInto(VIABILITY_TESTS)
              .set(ACCESSION_ID, accessionId)
              .set(END_DATE, calculatedTest.endDate)
              .set(NOTES, calculatedTest.notes)
              .set(REMAINING_GRAMS, calculatedTest.remaining?.grams)
              .set(REMAINING_QUANTITY, calculatedTest.remaining?.quantity)
              .set(REMAINING_UNITS_ID, calculatedTest.remaining?.units)
              .set(SEED_TYPE_ID, calculatedTest.seedType)
              .set(SEEDS_SOWN, calculatedTest.seedsSown)
              .set(STAFF_RESPONSIBLE, calculatedTest.staffResponsible)
              .set(START_DATE, calculatedTest.startDate)
              .set(SUBSTRATE_ID, calculatedTest.substrate)
              .set(TEST_TYPE, calculatedTest.testType)
              .set(TOTAL_PERCENT_GERMINATED, calculatedTest.totalPercentGerminated)
              .set(TOTAL_SEEDS_GERMINATED, calculatedTest.totalSeedsGerminated)
              .set(TREATMENT_ID, calculatedTest.treatment)
              .returning(ID)
              .fetchOne()
              ?.get(ID)!!
        }

    calculatedTest.testResults?.forEach { insertTestResult(testId, it) }

    return calculatedTest.copy(id = testId)
  }

  private fun insertTestResult(testId: ViabilityTestId, testResult: ViabilityTestResultModel) {
    dslContext
        .insertInto(VIABILITY_TEST_RESULTS)
        .set(VIABILITY_TEST_RESULTS.RECORDING_DATE, testResult.recordingDate)
        .set(VIABILITY_TEST_RESULTS.SEEDS_GERMINATED, testResult.seedsGerminated)
        .set(VIABILITY_TEST_RESULTS.TEST_ID, testId)
        .execute()
  }

  fun updateViabilityTests(
      accessionId: AccessionId,
      existingTests: List<ViabilityTestModel>?,
      desiredTests: List<ViabilityTestModel>?
  ) {
    val existing = existingTests ?: emptyList()
    val existingById = existing.associateBy { it.id }
    val existingIds = existingById.keys
    val desired = desiredTests ?: emptyList()
    val deletedTestIds = existingIds.minus(desired.mapNotNull { it.id }.toSet())

    if (deletedTestIds.isNotEmpty()) {
      dslContext
          .deleteFrom(VIABILITY_TESTS)
          .where(VIABILITY_TESTS.ID.`in`(deletedTestIds))
          .execute()
    }

    desired
        .map { it.withCalculatedValues() }
        .forEach { desiredTest ->
          val testId = desiredTest.id

          if (testId == null) {
            insertViabilityTest(accessionId, desiredTest)
          } else {
            val existingTest =
                existingById[testId]
                    ?: throw IllegalArgumentException(
                        "Viability test IDs must refer to existing tests; leave ID off to insert a new test.")
            if (!desiredTest.fieldsEqual(existingTest)) {
              with(VIABILITY_TESTS) {
                dslContext
                    .update(VIABILITY_TESTS)
                    .set(END_DATE, desiredTest.endDate)
                    .set(NOTES, desiredTest.notes)
                    .set(REMAINING_GRAMS, desiredTest.remaining?.grams)
                    .set(REMAINING_QUANTITY, desiredTest.remaining?.quantity)
                    .set(REMAINING_UNITS_ID, desiredTest.remaining?.units)
                    .set(SEED_TYPE_ID, desiredTest.seedType)
                    .set(SEEDS_SOWN, desiredTest.seedsSown)
                    .set(SUBSTRATE_ID, desiredTest.substrate)
                    .set(STAFF_RESPONSIBLE, desiredTest.staffResponsible)
                    .set(START_DATE, desiredTest.startDate)
                    .set(TOTAL_PERCENT_GERMINATED, desiredTest.totalPercentGerminated)
                    .set(TOTAL_SEEDS_GERMINATED, desiredTest.totalSeedsGerminated)
                    .set(TREATMENT_ID, desiredTest.treatment)
                    .where(ID.eq(testId))
                    .execute()
              }
            }

            // TODO: Smarter diff of test results
            dslContext
                .deleteFrom(VIABILITY_TEST_RESULTS)
                .where(VIABILITY_TEST_RESULTS.TEST_ID.eq(testId))
                .execute()
            desiredTest.testResults?.forEach { insertTestResult(testId, it) }
          }
        }
  }
}
