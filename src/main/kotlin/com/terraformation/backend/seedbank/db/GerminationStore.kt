package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.GerminationTestId
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.seedbank.model.GerminationModel
import com.terraformation.backend.seedbank.model.GerminationTestModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@ManagedBean
class GerminationStore(private val dslContext: DSLContext) {
  fun germinationTestTypesMultiset(
      idField: Field<AccessionId?> = ACCESSIONS.ID
  ): Field<Set<GerminationTestType>> {
    return DSL.multiset(
            DSL.select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
                .from(ACCESSION_GERMINATION_TEST_TYPES)
                .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(idField)))
        .convertFrom { result -> result.map { it.value1() }.toSet() }
  }

  fun germinationTestsMultiset(
      idField: Field<AccessionId?> = ACCESSIONS.ID
  ): Field<List<GerminationTestModel>> {
    val germinationsMultiset = germinationsMultiset()

    return with(GERMINATION_TESTS) {
      DSL.multiset(
              DSL.select(GERMINATION_TESTS.asterisk(), germinationsMultiset)
                  .from(GERMINATION_TESTS)
                  .where(ACCESSION_ID.eq(idField))
                  .orderBy(ID))
          .convertFrom { result ->
            result.map { record ->
              GerminationTestModel(
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
                  record[germinationsMultiset]?.ifEmpty { null },
                  SeedQuantityModel.of(record[REMAINING_QUANTITY], record[REMAINING_UNITS_ID]),
              )
            }
          }
    }
  }

  private fun germinationsMultiset(): Field<List<GerminationModel>> {
    return DSL.multiset(
            DSL.select(
                    GERMINATIONS.ID,
                    GERMINATIONS.TEST_ID,
                    GERMINATIONS.RECORDING_DATE,
                    GERMINATIONS.SEEDS_GERMINATED,
                )
                .from(GERMINATIONS)
                .where(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
                .orderBy(GERMINATIONS.RECORDING_DATE.desc(), GERMINATIONS.ID.desc()),
        )
        .convertFrom { result ->
          result.map { record ->
            GerminationModel(
                record[GERMINATIONS.ID]!!,
                record[GERMINATIONS.TEST_ID]!!,
                record[GERMINATIONS.RECORDING_DATE]!!,
                record[GERMINATIONS.SEEDS_GERMINATED]!!,
            )
          }
        }
  }

  fun insertGerminationTest(
      accessionId: AccessionId,
      germinationTest: GerminationTestModel
  ): GerminationTestModel {
    val calculatedTest: GerminationTestModel = germinationTest.withCalculatedValues()

    val testId =
        with(GERMINATION_TESTS) {
          dslContext
              .insertInto(GERMINATION_TESTS)
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

    calculatedTest.germinations?.forEach { insertGermination(testId, it) }

    return calculatedTest.copy(id = testId)
  }

  private fun insertGermination(testId: GerminationTestId, germination: GerminationModel) {
    dslContext
        .insertInto(GERMINATIONS)
        .set(GERMINATIONS.RECORDING_DATE, germination.recordingDate)
        .set(GERMINATIONS.SEEDS_GERMINATED, germination.seedsGerminated)
        .set(GERMINATIONS.TEST_ID, testId)
        .execute()
  }

  fun updateGerminationTestTypes(
      accessionId: AccessionId,
      existingTypes: Set<GerminationTestType>?,
      desiredTypes: Set<GerminationTestType>?
  ) {
    val existing = existingTypes ?: emptySet()
    val desired = desiredTypes ?: emptySet()
    val added = desired.minus(existing)
    val deleted = existing.minus(desired)

    if (deleted.isNotEmpty()) {
      dslContext
          .deleteFrom(ACCESSION_GERMINATION_TEST_TYPES)
          .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(accessionId))
          .and(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID.`in`(deleted))
          .execute()
    }

    added.forEach { type ->
      dslContext
          .insertInto(ACCESSION_GERMINATION_TEST_TYPES)
          .set(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID, accessionId)
          .set(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID, type)
          .execute()
    }
  }

  fun updateGerminationTests(
      accessionId: AccessionId,
      existingTests: List<GerminationTestModel>?,
      desiredTests: List<GerminationTestModel>?
  ) {
    val existing = existingTests ?: emptyList()
    val existingById = existing.associateBy { it.id }
    val existingIds = existingById.keys
    val desired = desiredTests ?: emptyList()
    val deletedTestIds = existingIds.minus(desired.mapNotNull { it.id }.toSet())

    if (deletedTestIds.isNotEmpty()) {
      dslContext.deleteFrom(GERMINATIONS).where(GERMINATIONS.TEST_ID.`in`(deletedTestIds)).execute()
      dslContext
          .deleteFrom(GERMINATION_TESTS)
          .where(GERMINATION_TESTS.ID.`in`(deletedTestIds))
          .execute()
    }

    desired
        .map { it.withCalculatedValues() }
        .forEach { desiredTest ->
          val testId = desiredTest.id

          if (testId == null) {
            insertGerminationTest(accessionId, desiredTest)
          } else {
            val existingTest =
                existingById[testId]
                    ?: throw IllegalArgumentException(
                        "Germination test IDs must refer to existing tests; leave ID off to insert a new test.")
            if (!desiredTest.fieldsEqual(existingTest)) {
              with(GERMINATION_TESTS) {
                dslContext
                    .update(GERMINATION_TESTS)
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

            // TODO: Smarter diff of germinations
            dslContext.deleteFrom(GERMINATIONS).where(GERMINATIONS.TEST_ID.eq(testId)).execute()
            desiredTest.germinations?.forEach { insertGermination(testId, it) }
          }
        }
  }
}
