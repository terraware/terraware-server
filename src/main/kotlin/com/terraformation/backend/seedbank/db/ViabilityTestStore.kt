package com.terraformation.backend.seedbank.db

import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TEST_RESULTS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

@Named
class ViabilityTestStore(private val dslContext: DSLContext) {
  fun fetchOneById(viabilityTestId: ViabilityTestId): ViabilityTestModel {
    requirePermissions { readViabilityTest(viabilityTestId) }

    val viabilityTestResultsMultiset = viabilityTestResultsMultiset()

    return dslContext
        .select(
            VIABILITY_TESTS.asterisk(),
            USERS.ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            viabilityTestResultsMultiset,
        )
        .from(VIABILITY_TESTS)
        .leftJoin(WITHDRAWALS)
        .on(VIABILITY_TESTS.ID.eq(WITHDRAWALS.VIABILITY_TEST_ID))
        .leftJoin(USERS)
        .on(WITHDRAWALS.WITHDRAWN_BY.eq(USERS.ID))
        .where(VIABILITY_TESTS.ID.eq(viabilityTestId))
        .fetchOne { record -> convertToModel(record, viabilityTestResultsMultiset) }
        ?: throw ViabilityTestNotFoundException(viabilityTestId)
  }

  fun fetchViabilityTests(accessionId: AccessionId): List<ViabilityTestModel> {
    requirePermissions { readAccession(accessionId) }

    val viabilityTestResultsMultiset = viabilityTestResultsMultiset()

    return dslContext
        .select(
            VIABILITY_TESTS.asterisk(),
            USERS.ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            viabilityTestResultsMultiset,
        )
        .from(VIABILITY_TESTS)
        .leftJoin(WITHDRAWALS)
        .on(VIABILITY_TESTS.ID.eq(WITHDRAWALS.VIABILITY_TEST_ID))
        .leftJoin(USERS)
        .on(WITHDRAWALS.WITHDRAWN_BY.eq(USERS.ID))
        .where(VIABILITY_TESTS.ACCESSION_ID.eq(accessionId))
        .orderBy(VIABILITY_TESTS.ID)
        .fetch { record -> convertToModel(record, viabilityTestResultsMultiset) }
  }

  fun viabilityTestsMultiset(
      idField: Field<AccessionId?> = ACCESSIONS.ID
  ): Field<List<ViabilityTestModel>> {
    val viabilityTestResultsMultiset = viabilityTestResultsMultiset()

    return with(VIABILITY_TESTS) {
      DSL.multiset(
              DSL.select(
                      VIABILITY_TESTS.asterisk(),
                      USERS.ID,
                      USERS.FIRST_NAME,
                      USERS.LAST_NAME,
                      viabilityTestResultsMultiset,
                  )
                  .from(VIABILITY_TESTS)
                  .leftJoin(WITHDRAWALS)
                  .on(VIABILITY_TESTS.ID.eq(WITHDRAWALS.VIABILITY_TEST_ID))
                  .leftJoin(USERS)
                  .on(WITHDRAWALS.WITHDRAWN_BY.eq(USERS.ID))
                  .where(ACCESSION_ID.eq(idField))
                  .orderBy(ID)
          )
          .convertFrom { result ->
            result.map { record -> convertToModel(record, viabilityTestResultsMultiset) }
          }
    }
  }

  private fun convertToModel(
      record: Record,
      viabilityTestResultsMultiset: Field<List<ViabilityTestResultModel>>,
  ): ViabilityTestModel {
    return with(VIABILITY_TESTS) {
      ViabilityTestModel(
          accessionId = record[ACCESSION_ID]!!,
          endDate = record[END_DATE],
          id = record[ID]!!,
          notes = record[NOTES],
          seedsCompromised = record[SEEDS_COMPROMISED],
          seedsEmpty = record[SEEDS_EMPTY],
          seedsFilled = record[SEEDS_FILLED],
          seedsTested = record[SEEDS_SOWN],
          seedType = record[SEED_TYPE_ID],
          staffResponsible = record[STAFF_RESPONSIBLE],
          startDate = record[START_DATE],
          substrate = record[SUBSTRATE_ID],
          testResults = record[viabilityTestResultsMultiset]?.ifEmpty { null },
          testType = record[TEST_TYPE]!!,
          totalSeedsGerminated = record[TOTAL_SEEDS_GERMINATED],
          treatment = record[TREATMENT_ID],
          viabilityPercent = record[TOTAL_PERCENT_GERMINATED],
          withdrawnByName =
              TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME]),
          withdrawnByUserId = record[USERS.ID],
      )
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
                    VIABILITY_TEST_RESULTS.RECORDING_DATE.desc(),
                    VIABILITY_TEST_RESULTS.ID.desc(),
                ),
        )
        .convertFrom { result ->
          result.map { record ->
            ViabilityTestResultModel(
                record[VIABILITY_TEST_RESULTS.ID]!!,
                record[VIABILITY_TEST_RESULTS.RECORDING_DATE]!!,
                record[VIABILITY_TEST_RESULTS.SEEDS_GERMINATED]!!,
                record[VIABILITY_TEST_RESULTS.TEST_ID]!!,
            )
          }
        }
  }

  fun insertViabilityTest(
      accessionId: AccessionId,
      viabilityTest: ViabilityTestModel,
  ): ViabilityTestModel {
    val calculatedTest: ViabilityTestModel = viabilityTest.withCalculatedValues()

    val testId =
        with(VIABILITY_TESTS) {
          dslContext
              .insertInto(VIABILITY_TESTS)
              .set(ACCESSION_ID, accessionId)
              .set(END_DATE, calculatedTest.endDate)
              .set(NOTES, calculatedTest.notes)
              .set(SEED_TYPE_ID, calculatedTest.seedType)
              .set(SEEDS_COMPROMISED, calculatedTest.seedsCompromised)
              .set(SEEDS_EMPTY, calculatedTest.seedsEmpty)
              .set(SEEDS_FILLED, calculatedTest.seedsFilled)
              .set(SEEDS_SOWN, calculatedTest.seedsTested)
              .set(STAFF_RESPONSIBLE, calculatedTest.staffResponsible)
              .set(START_DATE, calculatedTest.startDate)
              .set(SUBSTRATE_ID, calculatedTest.substrate)
              .set(TEST_TYPE, calculatedTest.testType)
              .set(TOTAL_PERCENT_GERMINATED, calculatedTest.viabilityPercent)
              .set(TOTAL_SEEDS_GERMINATED, calculatedTest.totalSeedsGerminated)
              .set(TREATMENT_ID, calculatedTest.treatment)
              .returning(ID)
              .fetchOne(ID)!!
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
      desiredTests: List<ViabilityTestModel>?,
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
                        "Viability test IDs must refer to existing tests; leave ID off to insert a new test."
                    )
            if (!desiredTest.fieldsEqual(existingTest)) {
              with(VIABILITY_TESTS) {
                dslContext
                    .update(VIABILITY_TESTS)
                    .set(END_DATE, desiredTest.endDate)
                    .set(NOTES, desiredTest.notes)
                    .set(SEED_TYPE_ID, desiredTest.seedType)
                    .set(SEEDS_COMPROMISED, desiredTest.seedsCompromised)
                    .set(SEEDS_EMPTY, desiredTest.seedsEmpty)
                    .set(SEEDS_FILLED, desiredTest.seedsFilled)
                    .set(SEEDS_SOWN, desiredTest.seedsTested)
                    .set(SUBSTRATE_ID, desiredTest.substrate)
                    .set(STAFF_RESPONSIBLE, desiredTest.staffResponsible)
                    .set(START_DATE, desiredTest.startDate)
                    .set(TOTAL_PERCENT_GERMINATED, desiredTest.viabilityPercent)
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
