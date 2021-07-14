package com.terraformation.backend.db

import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.model.GerminationModel
import com.terraformation.backend.model.GerminationTestModel
import com.terraformation.backend.model.SeedQuantityModel
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class GerminationStore(private val dslContext: DSLContext) {
  fun fetchGerminationTestTypes(accessionId: Long): Set<GerminationTestType> {
    return dslContext
        .select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
        .from(ACCESSION_GERMINATION_TEST_TYPES)
        .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(accessionId))
        .fetch(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
        .filterNotNull()
        .toSet()
  }

  fun fetchGerminationTests(accessionId: Long): List<GerminationTestModel> {
    val germinationsByTestId =
        dslContext
            .select(
                GERMINATIONS.ID,
                GERMINATIONS.TEST_ID,
                GERMINATIONS.RECORDING_DATE,
                GERMINATIONS.SEEDS_GERMINATED,
            )
            .from(GERMINATIONS)
            .join(GERMINATION_TESTS)
            .on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
            .where(GERMINATION_TESTS.ACCESSION_ID.eq(accessionId))
            .orderBy(GERMINATIONS.RECORDING_DATE.desc(), GERMINATIONS.ID.desc())
            .fetch { record ->
              GerminationModel(
                  record[GERMINATIONS.ID]!!,
                  record[GERMINATIONS.TEST_ID]!!,
                  record[GERMINATIONS.RECORDING_DATE]!!,
                  record[GERMINATIONS.SEEDS_GERMINATED]!!,
              )
            }
            .groupBy { it.testId }

    return with(GERMINATION_TESTS) {
      dslContext
          .selectFrom(GERMINATION_TESTS)
          .where(ACCESSION_ID.eq(accessionId))
          .orderBy(ID)
          .fetch { record ->
            val testId = record[ID]!!
            GerminationTestModel(
                testId,
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
                germinationsByTestId[testId],
                SeedQuantityModel.of(record[REMAINING_QUANTITY], record[REMAINING_UNITS_ID]),
            )
          }
    }
  }

  fun insertGerminationTest(
      accessionId: Long,
      germinationTest: GerminationTestModel
  ): GerminationTestModel {
    val testId =
        with(GERMINATION_TESTS) {
          dslContext
              .insertInto(GERMINATION_TESTS)
              .set(ACCESSION_ID, accessionId)
              .set(END_DATE, germinationTest.endDate)
              .set(NOTES, germinationTest.notes)
              .set(REMAINING_GRAMS, germinationTest.remaining?.grams)
              .set(REMAINING_QUANTITY, germinationTest.remaining?.quantity)
              .set(REMAINING_UNITS_ID, germinationTest.remaining?.units)
              .set(SEED_TYPE_ID, germinationTest.seedType)
              .set(SEEDS_SOWN, germinationTest.seedsSown)
              .set(STAFF_RESPONSIBLE, germinationTest.staffResponsible)
              .set(START_DATE, germinationTest.startDate)
              .set(SUBSTRATE_ID, germinationTest.substrate)
              .set(TEST_TYPE, germinationTest.testType)
              .set(TOTAL_PERCENT_GERMINATED, germinationTest.calculateTotalPercentGerminated())
              .set(TOTAL_SEEDS_GERMINATED, germinationTest.calculateTotalSeedsGerminated())
              .set(TREATMENT_ID, germinationTest.treatment)
              .returning(ID)
              .fetchOne()
              ?.get(ID)!!
        }

    germinationTest.germinations?.forEach { insertGermination(testId, it) }

    return germinationTest.copy(id = testId)
  }

  private fun insertGermination(testId: Long, germination: GerminationModel) {
    dslContext
        .insertInto(GERMINATIONS)
        .set(GERMINATIONS.RECORDING_DATE, germination.recordingDate)
        .set(GERMINATIONS.SEEDS_GERMINATED, germination.seedsGerminated)
        .set(GERMINATIONS.TEST_ID, testId)
        .execute()
  }

  fun updateGerminationTestTypes(
      accessionId: Long,
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
      accessionId: Long,
      existingTests: List<GerminationTestModel>?,
      desiredTests: List<GerminationTestModel>?
  ) {
    val existing = existingTests ?: emptyList()
    val existingById = existing.associateBy { it.id }
    val existingIds = existingById.keys
    val desired = desiredTests ?: emptyList()
    val deletedTestIds = existingIds.minus(desired.mapNotNull { it.id })

    if (deletedTestIds.isNotEmpty()) {
      dslContext.deleteFrom(GERMINATIONS).where(GERMINATIONS.TEST_ID.`in`(deletedTestIds)).execute()
      dslContext
          .deleteFrom(GERMINATION_TESTS)
          .where(GERMINATION_TESTS.ID.`in`(deletedTestIds))
          .execute()
    }

    desired.forEach { desiredTest ->
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
                .set(TOTAL_PERCENT_GERMINATED, desiredTest.calculateTotalPercentGerminated())
                .set(TOTAL_SEEDS_GERMINATED, desiredTest.calculateTotalSeedsGerminated())
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
