package com.terraformation.seedbank.db

import com.terraformation.seedbank.TerrawareServerConfig
import com.terraformation.seedbank.api.seedbank.Geolocation
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTOR
import com.terraformation.seedbank.db.tables.references.BAG
import com.terraformation.seedbank.db.tables.references.COLLECTION_EVENT
import com.terraformation.seedbank.db.tables.references.COLLECTOR
import com.terraformation.seedbank.db.tables.references.GERMINATION
import com.terraformation.seedbank.db.tables.references.GERMINATION_TEST
import com.terraformation.seedbank.db.tables.references.SPECIES
import com.terraformation.seedbank.db.tables.references.SPECIES_FAMILY
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.AccessionModel
import com.terraformation.seedbank.model.ConcreteAccession
import com.terraformation.seedbank.model.GerminationFields
import com.terraformation.seedbank.model.GerminationModel
import com.terraformation.seedbank.model.GerminationTestFields
import com.terraformation.seedbank.model.GerminationTestModel
import com.terraformation.seedbank.services.perClassLogger
import com.terraformation.seedbank.services.toSetOrNull
import java.math.BigDecimal
import java.time.Clock
import javax.annotation.ManagedBean
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.truncate
import kotlin.random.Random
import org.jooq.DSLContext
import org.jooq.InsertSetMoreStep
import org.jooq.Record
import org.jooq.TableField
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class AccessionFetcher(
    private val dslContext: DSLContext,
    private val config: TerrawareServerConfig
) {
  companion object {
    /** Number of times to try generating a unique accession number before giving up. */
    private const val ACCESSION_NUMBER_RETRIES = 10
  }

  var clock = Clock.systemUTC()!!
  var accessionNumberGenerator = AccessionNumberGenerator()

  private val log = perClassLogger()

  fun fetchByNumber(accessionNumber: String): AccessionModel? {
    // First, fetch all the values that are either directly on the accession table or are in other
    // tables such that there is at most one value for a given accession (N:1 relation).
    val parentRow =
        dslContext
            .select(
                ACCESSION.asterisk(),
                ACCESSION.collector().NAME,
                ACCESSION.species().NAME,
                ACCESSION.speciesFamily().NAME,
                ACCESSION.STATE_ID,
                ACCESSION.storageLocation().NAME,
                ACCESSION.storageLocation().CONDITION_ID,
                ACCESSION.TARGET_STORAGE_CONDITION,
                ACCESSION.PROCESSING_METHOD_ID,
                ACCESSION.PROCESSING_STAFF_RESPONSIBLE,
            )
            .from(ACCESSION)
            .where(ACCESSION.NUMBER.eq(accessionNumber))
            .fetchOne()
            ?: return null

    // Now populate all the items that there can be many of per accession.
    val accessionId = parentRow[ACCESSION.ID]!!

    val secondaryCollectorNames =
        dslContext
            .select(COLLECTOR.NAME)
            .from(COLLECTOR)
            .join(ACCESSION_SECONDARY_COLLECTOR)
            .on(COLLECTOR.ID.eq(ACCESSION_SECONDARY_COLLECTOR.COLLECTOR_ID))
            .where(ACCESSION_SECONDARY_COLLECTOR.ACCESSION_ID.eq(accessionId))
            .orderBy(COLLECTOR.NAME)
            .fetch(COLLECTOR.NAME)
            .toSetOrNull()

    val bagNumbers =
        dslContext
            .select(BAG.LABEL)
            .from(BAG)
            .where(BAG.ACCESSION_ID.eq(accessionId))
            .orderBy(BAG.LABEL)
            .fetch(BAG.LABEL)
            .toSetOrNull()

    val geolocations =
        dslContext
            .selectFrom(COLLECTION_EVENT)
            .where(COLLECTION_EVENT.ACCESSION_ID.eq(accessionId))
            .orderBy(COLLECTION_EVENT.LATITUDE, COLLECTION_EVENT.LONGITUDE)
            .fetch { record ->
              Geolocation(
                  record[COLLECTION_EVENT.LATITUDE]!!,
                  record[COLLECTION_EVENT.LONGITUDE]!!,
                  record[COLLECTION_EVENT.GPS_ACCURACY]?.let { BigDecimal(it) })
            }
            .toSetOrNull()

    val germinationsByTestId =
        dslContext
            .select(
                GERMINATION.ID,
                GERMINATION.TEST_ID,
                GERMINATION.RECORDING_DATE,
                GERMINATION.SEEDS_GERMINATED)
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
                  record[GERMINATION.SEEDS_GERMINATED]!!)
            }
            .groupBy { it.testId }

    val germinationTests =
        dslContext
            .select(
                GERMINATION_TEST.asterisk(),
                GERMINATION_TEST.TREATMENT_ID,
                GERMINATION_TEST.SEED_TYPE_ID,
                GERMINATION_TEST.SUBSTRATE_ID)
            .from(GERMINATION_TEST)
            .orderBy(GERMINATION_TEST.ID)
            .fetch { record ->
              val testId = record[GERMINATION_TEST.ID]!!
              GerminationTestModel(
                  testId,
                  record[GERMINATION_TEST.ACCESSION_ID]!!,
                  record[GERMINATION_TEST.TEST_TYPE]!!,
                  record[GERMINATION_TEST.START_DATE],
                  record[GERMINATION_TEST.SEED_TYPE_ID],
                  record[GERMINATION_TEST.SUBSTRATE_ID],
                  record[GERMINATION_TEST.TREATMENT_ID],
                  record[GERMINATION_TEST.SEEDS_SOWN],
                  record[GERMINATION_TEST.NOTES],
                  germinationsByTestId[testId])
            }
            .toSetOrNull()

    // TODO: Move this logic
    val status =
        if (parentRow[ACCESSION.STATE_ID] == AccessionState.Withdrawn) "Inactive" else "Active"

    return AccessionModel(
        id = accessionId,
        accessionNumber = accessionNumber,
        state = parentRow[ACCESSION.STATE_ID]!!,
        status = status,
        species = parentRow[ACCESSION.species().NAME],
        family = parentRow[ACCESSION.speciesFamily().NAME],
        numberOfTrees = parentRow[ACCESSION.COLLECTION_TREES],
        founderId = parentRow[ACCESSION.FOUNDER_TREE],
        endangered = parentRow[ACCESSION.SPECIES_ENDANGERED],
        rare = parentRow[ACCESSION.SPECIES_RARE],
        fieldNotes = parentRow[ACCESSION.FIELD_NOTES],
        collectedDate = parentRow[ACCESSION.COLLECTED_DATE],
        receivedDate = parentRow[ACCESSION.RECEIVED_DATE],
        primaryCollector = parentRow[ACCESSION.collector().NAME],
        secondaryCollectors = secondaryCollectorNames,
        siteLocation = parentRow[ACCESSION.COLLECTION_SITE_NAME],
        landowner = parentRow[ACCESSION.COLLECTION_SITE_LANDOWNER],
        environmentalNotes = parentRow[ACCESSION.COLLECTION_SITE_NOTES],
        processingStartDate = parentRow[ACCESSION.PROCESSING_START_DATE],
        processingMethod = parentRow[ACCESSION.PROCESSING_METHOD_ID],
        seedsCounted = parentRow[ACCESSION.SEEDS_COUNTED],
        subsetWeightGrams = parentRow[ACCESSION.SUBSET_WEIGHT],
        totalWeightGrams = parentRow[ACCESSION.TOTAL_WEIGHT],
        subsetCount = parentRow[ACCESSION.SUBSET_COUNT],
        estimatedSeedCount = parentRow[ACCESSION.EST_SEED_COUNT],
        targetStorageCondition = parentRow[ACCESSION.TARGET_STORAGE_CONDITION],
        dryingStartDate = parentRow[ACCESSION.DRYING_START_DATE],
        dryingEndDate = parentRow[ACCESSION.DRYING_END_DATE],
        dryingMoveDate = parentRow[ACCESSION.DRYING_MOVE_DATE],
        processingNotes = parentRow[ACCESSION.PROCESSING_NOTES],
        processingStaffResponsible = parentRow[ACCESSION.PROCESSING_STAFF_RESPONSIBLE],
        bagNumbers = bagNumbers,
        geolocations = geolocations,
        photoFilenames = null, // TODO (need this in the data model),
        germinationTests = germinationTests,
    )
  }

  fun create(accession: AccessionFields): ConcreteAccession {
    var attemptsRemaining = ACCESSION_NUMBER_RETRIES

    while (attemptsRemaining-- > 0) {
      val accessionNumber = accessionNumberGenerator.generateAccessionNumber()

      try {
        dslContext.transaction { _ ->
          val accessionId =
              with(ACCESSION) {
                dslContext
                    .insertInto(ACCESSION)
                    .set(NUMBER, accessionNumber)
                    .set(SITE_MODULE_ID, config.siteModuleId)
                    .set(CREATED_TIME, clock.instant())
                    .set(STATE_ID, AccessionState.Pending)
                    .set(SPECIES_ID, getSpeciesId(accession.species))
                    .set(SPECIES_FAMILY_ID, getSpeciesFamilyId(accession.family))
                    .set(COLLECTION_TREES, accession.numberOfTrees)
                    .set(FOUNDER_TREE, accession.founderId)
                    .set(SPECIES_ENDANGERED, accession.endangered)
                    .set(SPECIES_RARE, accession.rare)
                    .set(FIELD_NOTES, accession.fieldNotes)
                    .set(COLLECTED_DATE, accession.collectedDate)
                    .set(RECEIVED_DATE, accession.receivedDate)
                    .set(PRIMARY_COLLECTOR_ID, getCollectorId(accession.primaryCollector))
                    .set(COLLECTION_SITE_NAME, accession.siteLocation)
                    .set(COLLECTION_SITE_LANDOWNER, accession.landowner)
                    .set(COLLECTION_SITE_NOTES, accession.environmentalNotes)
                    .returning(ID)
                    .fetchOne()
                    ?.get(ID)!!
              }

          insertSecondaryCollectors(accessionId, accession.secondaryCollectors)
          updateBags(accessionId, emptySet(), accession.bagNumbers)
          updateGeolocations(accessionId, emptySet(), accession.geolocations)
          updateGerminationTests(accessionId, emptySet(), accession.germinationTests)
        }

        return fetchByNumber(accessionNumber)!!
      } catch (ex: DuplicateKeyException) {
        log.info("Accession number $accessionNumber already existed; trying again")
        if (attemptsRemaining <= 0) {
          log.error("Unable to generate unique accession number")
          throw ex
        }
      }
    }

    throw RuntimeException("BUG! Inserting accession failed but error was not caught.")
  }

  fun update(accessionNumber: String, accession: AccessionFields): Boolean {
    val existing = fetchByNumber(accessionNumber) ?: return false
    val accessionId = existing.id

    dslContext.transaction { _ ->
      val rowsUpdated =
          with(ACCESSION) {
            dslContext
                .update(ACCESSION)
                .set(SPECIES_ID, getSpeciesId(accession.species))
                .set(SPECIES_FAMILY_ID, getSpeciesFamilyId(accession.family))
                .set(COLLECTION_TREES, accession.numberOfTrees)
                .set(FOUNDER_TREE, accession.founderId)
                .set(SPECIES_ENDANGERED, accession.endangered)
                .set(SPECIES_RARE, accession.rare)
                .set(FIELD_NOTES, accession.fieldNotes)
                .set(COLLECTED_DATE, accession.collectedDate)
                .set(RECEIVED_DATE, accession.receivedDate)
                .set(PRIMARY_COLLECTOR_ID, getCollectorId(accession.primaryCollector))
                .set(PROCESSING_START_DATE, accession.processingStartDate)
                .set(PROCESSING_METHOD_ID, accession.processingMethod)
                .set(SEEDS_COUNTED, accession.seedsCounted)
                .set(SUBSET_WEIGHT, accession.subsetWeightGrams)
                .set(TOTAL_WEIGHT, accession.totalWeightGrams)
                .set(SUBSET_COUNT, accession.subsetCount)
                .set(EST_SEED_COUNT, accession.estimatedSeedCount)
                .set(TARGET_STORAGE_CONDITION, accession.targetStorageCondition)
                .set(DRYING_START_DATE, accession.dryingStartDate)
                .set(DRYING_END_DATE, accession.dryingEndDate)
                .set(DRYING_MOVE_DATE, accession.dryingMoveDate)
                .set(PROCESSING_NOTES, accession.processingNotes)
                .set(PROCESSING_STAFF_RESPONSIBLE, accession.processingStaffResponsible)
                .set(COLLECTION_SITE_NAME, accession.siteLocation)
                .set(COLLECTION_SITE_LANDOWNER, accession.landowner)
                .set(COLLECTION_SITE_NOTES, accession.environmentalNotes)
                .where(NUMBER.eq(accessionNumber))
                .execute()
          }

      if (rowsUpdated != 1) {
        log.error("Accession $accessionNumber exists in database but update failed")
        throw DataAccessException("Unable to update accession $accessionNumber")
      }

      // TODO: Photo filenames (if it makes sense to make these updatable)

      if (existing.secondaryCollectors != accession.secondaryCollectors) {
        // TODO: More selective update
        dslContext
            .deleteFrom(ACCESSION_SECONDARY_COLLECTOR)
            .where(ACCESSION_SECONDARY_COLLECTOR.ACCESSION_ID.eq(accessionId))
            .execute()
        insertSecondaryCollectors(accessionId, accession.secondaryCollectors)
      }

      updateBags(accessionId, existing.bagNumbers, accession.bagNumbers)
      updateGeolocations(accessionId, existing.geolocations, accession.geolocations)
      updateGerminationTests(accessionId, existing.germinationTests, accession.germinationTests)
    }

    return true
  }

  private fun updateBags(
      accessionId: Long,
      existingBagNumbers: Set<String>?,
      desiredBagNumbers: Set<String>?
  ) {
    if (existingBagNumbers != desiredBagNumbers) {
      val existing = existingBagNumbers ?: emptySet()
      val desired = desiredBagNumbers ?: emptySet()
      val deletedBagNumbers = existing.minus(desired)
      val addedBagNumbers = desired.minus(existing)

      if (deletedBagNumbers.isNotEmpty()) {
        dslContext
            .deleteFrom(BAG)
            .where(BAG.ACCESSION_ID.eq(accessionId))
            .and(BAG.LABEL.`in`(deletedBagNumbers))
            .execute()
      }

      addedBagNumbers.forEach { bagNumber ->
        dslContext
            .insertInto(BAG, BAG.ACCESSION_ID, BAG.LABEL)
            .values(accessionId, bagNumber)
            .execute()
      }
    }
  }

  private fun updateGeolocations(
      accessionId: Long,
      existingGeolocations: Set<Geolocation>?,
      desiredGeolocations: Set<Geolocation>?
  ) {
    if (existingGeolocations != desiredGeolocations) {
      val existing = existingGeolocations ?: emptySet()
      val desired = desiredGeolocations ?: emptySet()
      val deleted = existing.minus(desired)
      val added = desired.minus(existing)

      with(COLLECTION_EVENT) {
        if (deleted.isNotEmpty()) {
          dslContext
              .deleteFrom(COLLECTION_EVENT)
              .where(ACCESSION_ID.eq(accessionId))
              .and(
                  DSL.row(LATITUDE, LONGITUDE)
                      .`in`(deleted.map { DSL.row(it.latitude, it.longitude) }))
              .execute()
        }

        added.forEach { geolocation ->
          dslContext
              .insertInto(
                  COLLECTION_EVENT, ACCESSION_ID, CREATED_TIME, LATITUDE, LONGITUDE, GPS_ACCURACY)
              .values(
                  accessionId,
                  clock.instant(),
                  geolocation.latitude,
                  geolocation.longitude,
                  geolocation.accuracy?.toDouble())
              .execute()
        }
      }
    }
  }

  private fun insertSecondaryCollectors(
      accessionId: Long,
      secondaryCollectors: Collection<String>?
  ) {
    if (secondaryCollectors != null) {
      val collectorIds = secondaryCollectors.map { name -> getCollectorId(name) }
      collectorIds.forEach { collectorId ->
        dslContext
            .insertInto(
                ACCESSION_SECONDARY_COLLECTOR,
                ACCESSION_SECONDARY_COLLECTOR.ACCESSION_ID,
                ACCESSION_SECONDARY_COLLECTOR.COLLECTOR_ID)
            .values(accessionId, collectorId)
            .execute()
      }
    }
  }

  private fun updateGerminationTests(
      accessionId: Long,
      existingTests: Set<GerminationTestModel>?,
      desiredTests: Set<GerminationTestFields>?
  ) {
    // Tests are identified by accession ID and test type, so just look at the types to determine
    // whether tests need to be added or removed. Only one test of each type is allowed.
    if (desiredTests?.groupBy { it.testType }?.values?.any { it.size > 1 } == true) {
      throw IllegalArgumentException("Cannot have multiple germination tests of the same type")
    }

    val existing = (existingTests ?: emptySet()).associateBy { it.testType }
    val desired = (desiredTests ?: emptySet()).associateBy { it.testType }
    val added = desired.minus(existing.keys)
    val deleted = existing.minus(desired.keys)
    val retained = existing.minus(added.keys).minus(deleted.keys)

    if (deleted.isNotEmpty()) {
      val deletedTestIds = deleted.values.map { it.id }
      dslContext.deleteFrom(GERMINATION).where(GERMINATION.TEST_ID.`in`(deletedTestIds)).execute()
      dslContext
          .deleteFrom(GERMINATION_TEST)
          .where(GERMINATION_TEST.ID.`in`(deletedTestIds))
          .execute()
    }

    added.values.forEach { insertGerminationTest(accessionId, it) }

    retained.values.forEach { existingTest ->
      val desiredTest = desired[existingTest.testType]!!
      if (!desiredTest.fieldsEqual(existingTest)) {
        dslContext
            .update(GERMINATION_TEST)
            .set(GERMINATION_TEST.NOTES, desiredTest.notes)
            .set(GERMINATION_TEST.SEED_TYPE_ID, desiredTest.seedType)
            .set(GERMINATION_TEST.SEEDS_SOWN, desiredTest.seedsSown)
            .set(GERMINATION_TEST.SUBSTRATE_ID, desiredTest.substrate)
            .set(GERMINATION_TEST.START_DATE, desiredTest.startDate)
            .set(GERMINATION_TEST.TREATMENT_ID, desiredTest.treatment)
            .where(GERMINATION_TEST.ID.eq(existingTest.id))
            .execute()
      }

      // TODO: Smarter diff of germinations
      dslContext.deleteFrom(GERMINATION).where(GERMINATION.TEST_ID.eq(existingTest.id)).execute()
      desiredTest.germinations?.forEach { insertGermination(existingTest.id, it) }
    }
  }

  private fun insertGerminationTest(accessionId: Long, germinationTest: GerminationTestFields) {
    val testId =
        dslContext
            .insertInto(GERMINATION_TEST)
            .set(GERMINATION_TEST.ACCESSION_ID, accessionId)
            .set(GERMINATION_TEST.NOTES, germinationTest.notes)
            .set(GERMINATION_TEST.SEED_TYPE_ID, germinationTest.seedType)
            .set(GERMINATION_TEST.SEEDS_SOWN, germinationTest.seedsSown)
            .set(GERMINATION_TEST.START_DATE, germinationTest.startDate)
            .set(GERMINATION_TEST.SUBSTRATE_ID, germinationTest.substrate)
            .set(GERMINATION_TEST.TEST_TYPE, germinationTest.testType)
            .set(GERMINATION_TEST.TREATMENT_ID, germinationTest.treatment)
            .returning(GERMINATION_TEST.ID)
            .fetchOne()
            ?.get(GERMINATION_TEST.ID)!!

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

  private fun getSpeciesId(speciesName: String?): Long? {
    return getOrInsertId(speciesName, SPECIES.ID, SPECIES.NAME) {
      it.set(SPECIES.CREATED_TIME, clock.instant())
      it.set(SPECIES.MODIFIED_TIME, clock.instant())
    }
  }

  private fun getSpeciesFamilyId(familyName: String?): Long? {
    return getOrInsertId(familyName, SPECIES_FAMILY.ID, SPECIES_FAMILY.NAME) {
      it.set(SPECIES_FAMILY.CREATED_TIME, clock.instant())
    }
  }

  private fun getCollectorId(name: String?): Long? {
    return getOrInsertId(name, COLLECTOR.ID, COLLECTOR.NAME) {
      it.set(COLLECTOR.SITE_MODULE_ID, config.siteModuleId)
    }
  }

  private fun getOrInsertId(
      name: String?,
      idField: TableField<*, Long?>,
      nameField: TableField<*, String?>,
      extraSetters: (InsertSetMoreStep<out Record>) -> Unit = {}
  ): Long? {
    if (name == null) {
      return null
    }

    val existingId =
        dslContext.select(idField).from(idField.table).where(nameField.eq(name)).fetchOne(idField)
    if (existingId != null) {
      return existingId
    }

    val table = idField.table!!

    return dslContext
        .insertInto(table)
        .set(nameField, name)
        .apply { extraSetters(this) }
        .returning(idField)
        .fetchOne()
        ?.get(idField)
        ?: throw DataAccessException("Unable to insert new ${table.name.toLowerCase()} $name")
  }

  private fun GerminationTestFields.fieldsEqual(other: GerminationTestFields): Boolean {
    return testType == other.testType &&
        treatment == other.treatment &&
        substrate == other.substrate &&
        startDate == other.startDate &&
        seedsSown == other.seedsSown &&
        seedType == other.seedType &&
        notes == other.notes
  }
}

class AccessionNumberGenerator {
  fun generateAccessionNumber(desiredBitsOfEntropy: Int = 40): String {
    // Use characters that are unlikely to be visually confused even when they are printed on
    // labels sitting inside plastic bags.
    val alphabet = "3479ACDEFHJKLMNPRTWY"
    val bitsPerCharacter = log2(alphabet.length.toFloat())
    val requiredLength = truncate(desiredBitsOfEntropy / bitsPerCharacter + 1).roundToInt()

    return 0.rangeTo(requiredLength)
        .map { alphabet[Random.nextInt(alphabet.length)] }
        .joinToString("")
  }
}
