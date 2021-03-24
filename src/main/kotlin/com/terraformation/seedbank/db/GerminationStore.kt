package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.ACCESSION_GERMINATION_TEST_TYPE
import com.terraformation.seedbank.db.tables.references.GERMINATION
import com.terraformation.seedbank.db.tables.references.GERMINATION_TEST
import com.terraformation.seedbank.model.GerminationFields
import com.terraformation.seedbank.model.GerminationModel
import com.terraformation.seedbank.model.GerminationTestFields
import com.terraformation.seedbank.model.GerminationTestModel
import com.terraformation.seedbank.services.toListOrNull
import com.terraformation.seedbank.services.toSetOrNull
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class GerminationStore(private val dslContext: DSLContext) {
  fun fetchGerminationTestTypes(accessionId: Long): Set<GerminationTestType>? {
    return dslContext
        .select(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)
        .from(ACCESSION_GERMINATION_TEST_TYPE)
        .where(ACCESSION_GERMINATION_TEST_TYPE.ACCESSION_ID.eq(accessionId))
        .fetch(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)
        .toSetOrNull()
  }

  fun fetchGerminationTests(accessionId: Long): List<GerminationTestModel>? {
    val germinationsByTestId =
        dslContext
            .select(
                GERMINATION.ID,
                GERMINATION.TEST_ID,
                GERMINATION.RECORDING_DATE,
                GERMINATION.SEEDS_GERMINATED,
            )
            .from(GERMINATION)
            .join(GERMINATION_TEST)
            .on(GERMINATION.TEST_ID.eq(GERMINATION_TEST.ID))
            .where(GERMINATION_TEST.ACCESSION_ID.eq(accessionId))
            .orderBy(GERMINATION.RECORDING_DATE.desc(), GERMINATION.ID.desc())
            .fetch { record ->
              GerminationModel(
                  record[GERMINATION.ID]!!,
                  record[GERMINATION.TEST_ID]!!,
                  record[GERMINATION.RECORDING_DATE]!!,
                  record[GERMINATION.SEEDS_GERMINATED]!!,
              )
            }
            .groupBy { it.testId }

    return with(GERMINATION_TEST) {
      dslContext
          .selectFrom(GERMINATION_TEST)
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
            )
          }
          .toListOrNull()
    }
  }

  private fun insertGerminationTest(accessionId: Long, germinationTest: GerminationTestFields) {
    val testId =
        with(GERMINATION_TEST) {
          dslContext
              .insertInto(GERMINATION_TEST)
              .set(ACCESSION_ID, accessionId)
              .set(END_DATE, germinationTest.endDate)
              .set(NOTES, germinationTest.notes)
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
  }

  private fun insertGermination(testId: Long, germination: GerminationFields) {
    dslContext
        .insertInto(GERMINATION)
        .set(GERMINATION.RECORDING_DATE, germination.recordingDate)
        .set(GERMINATION.SEEDS_GERMINATED, germination.seedsGerminated)
        .set(GERMINATION.TEST_ID, testId)
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
          .deleteFrom(ACCESSION_GERMINATION_TEST_TYPE)
          .where(ACCESSION_GERMINATION_TEST_TYPE.ACCESSION_ID.eq(accessionId))
          .and(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID.`in`(deleted))
          .execute()
    }

    added.forEach { type ->
      dslContext
          .insertInto(ACCESSION_GERMINATION_TEST_TYPE)
          .set(ACCESSION_GERMINATION_TEST_TYPE.ACCESSION_ID, accessionId)
          .set(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID, type)
          .execute()
    }
  }

  fun updateGerminationTests(
      accessionId: Long,
      existingTests: List<GerminationTestModel>?,
      desiredTests: List<GerminationTestFields>?
  ) {
    val existing = existingTests ?: emptyList()
    val existingById = existing.associateBy { it.id }
    val existingIds = existingById.keys
    val desired = desiredTests ?: emptyList()
    val deletedTestIds = existingIds.minus(desired.mapNotNull { it.id })

    if (deletedTestIds.isNotEmpty()) {
      dslContext.deleteFrom(GERMINATION).where(GERMINATION.TEST_ID.`in`(deletedTestIds)).execute()
      dslContext
          .deleteFrom(GERMINATION_TEST)
          .where(GERMINATION_TEST.ID.`in`(deletedTestIds))
          .execute()
    }

    desired.filter { it.id == null }.forEach { insertGerminationTest(accessionId, it) }

    desired.filter { it.id != null }.forEach { desiredTest ->
      val existingTest =
          existingById[desiredTest.id]
              ?: throw IllegalArgumentException(
                  "Germination test IDs must refer to existing tests; leave ID off to insert a new test.")
      if (!desiredTest.fieldsEqual(existingTest)) {
        with(GERMINATION_TEST) {
          dslContext
              .update(GERMINATION_TEST)
              .set(END_DATE, desiredTest.endDate)
              .set(NOTES, desiredTest.notes)
              .set(SEED_TYPE_ID, desiredTest.seedType)
              .set(SEEDS_SOWN, desiredTest.seedsSown)
              .set(SUBSTRATE_ID, desiredTest.substrate)
              .set(STAFF_RESPONSIBLE, desiredTest.staffResponsible)
              .set(START_DATE, desiredTest.startDate)
              .set(TOTAL_PERCENT_GERMINATED, desiredTest.calculateTotalPercentGerminated())
              .set(TOTAL_SEEDS_GERMINATED, desiredTest.calculateTotalSeedsGerminated())
              .set(TREATMENT_ID, desiredTest.treatment)
              .where(ID.eq(existingTest.id))
              .execute()
        }
      }

      // TODO: Smarter diff of germinations
      dslContext.deleteFrom(GERMINATION).where(GERMINATION.TEST_ID.eq(existingTest.id)).execute()
      desiredTest.germinations?.forEach { insertGermination(existingTest.id, it) }
    }
  }
}
