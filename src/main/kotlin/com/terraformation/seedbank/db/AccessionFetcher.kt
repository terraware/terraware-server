package com.terraformation.seedbank.db

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTOR
import com.terraformation.seedbank.db.tables.references.COLLECTOR
import com.terraformation.seedbank.db.tables.references.SPECIES
import com.terraformation.seedbank.db.tables.references.SPECIES_FAMILY
import com.terraformation.seedbank.db.tables.references.STORAGE_LOCATION
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.AccessionModel
import com.terraformation.seedbank.model.AccessionNumberGenerator
import com.terraformation.seedbank.model.ConcreteAccession
import com.terraformation.seedbank.services.perClassLogger
import com.terraformation.seedbank.services.toSetOrNull
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.InsertSetMoreStep
import org.jooq.Record
import org.jooq.TableField
import org.jooq.exception.DataAccessException
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class AccessionFetcher(
    private val dslContext: DSLContext,
    private val config: TerrawareServerConfig,
    private val bagFetcher: BagFetcher,
    private val collectionEventFetcher: CollectionEventFetcher,
    private val germinationFetcher: GerminationFetcher,
    private val withdrawalFetcher: WithdrawalFetcher,
    private val clock: Clock,
) {
  companion object {
    /** Number of times to try generating a unique accession number before giving up. */
    private const val ACCESSION_NUMBER_RETRIES = 10
  }

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
            .and(ACCESSION.SITE_MODULE_ID.eq(config.siteModuleId))
            .fetchOne()
            ?: return null

    // Now populate all the items that there can be many of per accession.
    val accessionId = parentRow[ACCESSION.ID]!!

    val secondaryCollectorNames = fetchSecondaryCollectorNames(accessionId)
    val bagNumbers = bagFetcher.fetchBagNumbers(accessionId)
    val geolocations = collectionEventFetcher.fetchGeolocations(accessionId)
    val germinationTestTypes = germinationFetcher.fetchGerminationTestTypes(accessionId)
    val germinationTests = germinationFetcher.fetchGerminationTests(accessionId)
    val withdrawals = withdrawalFetcher.fetchWithdrawals(accessionId)

    return with(ACCESSION) {
      AccessionModel(
          id = accessionId,
          accessionNumber = accessionNumber,
          state = parentRow[STATE_ID]!!,
          species = parentRow[species().NAME],
          family = parentRow[speciesFamily().NAME],
          numberOfTrees = parentRow[COLLECTION_TREES],
          founderId = parentRow[FOUNDER_TREE],
          endangered = parentRow[SPECIES_ENDANGERED],
          rare = parentRow[SPECIES_RARE],
          fieldNotes = parentRow[FIELD_NOTES],
          collectedDate = parentRow[COLLECTED_DATE],
          receivedDate = parentRow[RECEIVED_DATE],
          primaryCollector = parentRow[collector().NAME],
          secondaryCollectors = secondaryCollectorNames,
          siteLocation = parentRow[COLLECTION_SITE_NAME],
          landowner = parentRow[COLLECTION_SITE_LANDOWNER],
          environmentalNotes = parentRow[COLLECTION_SITE_NOTES],
          processingStartDate = parentRow[PROCESSING_START_DATE],
          processingMethod = parentRow[PROCESSING_METHOD_ID],
          seedsCounted = parentRow[SEEDS_COUNTED],
          subsetWeightGrams = parentRow[SUBSET_WEIGHT],
          totalWeightGrams = parentRow[TOTAL_WEIGHT],
          subsetCount = parentRow[SUBSET_COUNT],
          estimatedSeedCount = parentRow[EST_SEED_COUNT],
          targetStorageCondition = parentRow[TARGET_STORAGE_CONDITION],
          dryingStartDate = parentRow[DRYING_START_DATE],
          dryingEndDate = parentRow[DRYING_END_DATE],
          dryingMoveDate = parentRow[DRYING_MOVE_DATE],
          processingNotes = parentRow[PROCESSING_NOTES],
          processingStaffResponsible = parentRow[PROCESSING_STAFF_RESPONSIBLE],
          bagNumbers = bagNumbers,
          storageStartDate = parentRow[STORAGE_START_DATE],
          storagePackets = parentRow[STORAGE_PACKETS],
          storageLocation = parentRow[storageLocation().NAME],
          storageCondition = parentRow[storageLocation().CONDITION_ID],
          storageNotes = parentRow[STORAGE_NOTES],
          storageStaffResponsible = parentRow[STORAGE_STAFF_RESPONSIBLE],
          geolocations = geolocations,
          photoFilenames = null, // TODO (need this in the data model),
          germinationTestTypes = germinationTestTypes,
          germinationTests = germinationTests,
          withdrawals = withdrawals,
      )
    }
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
                    .set(STORAGE_START_DATE, accession.storageStartDate)
                    .set(STORAGE_PACKETS, accession.storagePackets)
                    .set(STORAGE_LOCATION_ID, getStorageLocationId(accession.storageLocation))
                    .set(STORAGE_NOTES, accession.storageNotes)
                    .set(STORAGE_STAFF_RESPONSIBLE, accession.storageStaffResponsible)
                    .returning(ID)
                    .fetchOne()
                    ?.get(ID)!!
              }

          insertSecondaryCollectors(accessionId, accession.secondaryCollectors)
          bagFetcher.updateBags(accessionId, emptySet(), accession.bagNumbers)
          collectionEventFetcher.updateGeolocations(accessionId, emptySet(), accession.geolocations)
          germinationFetcher.updateGerminationTestTypes(
              accessionId, emptySet(), accession.germinationTestTypes)
          germinationFetcher.updateGerminationTests(
              accessionId, emptyList(), accession.germinationTests)
          withdrawalFetcher.updateWithdrawals(
              accessionId, accession, emptyList(), accession.withdrawals)
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
                .set(STORAGE_START_DATE, accession.storageStartDate)
                .set(STORAGE_PACKETS, accession.storagePackets)
                .set(STORAGE_LOCATION_ID, getStorageLocationId(accession.storageLocation))
                .set(STORAGE_NOTES, accession.storageNotes)
                .set(STORAGE_STAFF_RESPONSIBLE, accession.storageStaffResponsible)
                .where(NUMBER.eq(accessionNumber))
                .and(SITE_MODULE_ID.eq(config.siteModuleId))
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

      bagFetcher.updateBags(accessionId, existing.bagNumbers, accession.bagNumbers)
      collectionEventFetcher.updateGeolocations(
          accessionId, existing.geolocations, accession.geolocations)
      germinationFetcher.updateGerminationTestTypes(
          accessionId, existing.germinationTestTypes, accession.germinationTestTypes)
      germinationFetcher.updateGerminationTests(
          accessionId, existing.germinationTests, accession.germinationTests)
      withdrawalFetcher.updateWithdrawals(
          accessionId, accession, existing.withdrawals, accession.withdrawals)
    }

    return true
  }

  private fun fetchSecondaryCollectorNames(accessionId: Long): Set<String>? {
    return dslContext
        .select(COLLECTOR.NAME)
        .from(COLLECTOR)
        .join(ACCESSION_SECONDARY_COLLECTOR)
        .on(COLLECTOR.ID.eq(ACCESSION_SECONDARY_COLLECTOR.COLLECTOR_ID))
        .where(ACCESSION_SECONDARY_COLLECTOR.ACCESSION_ID.eq(accessionId))
        .orderBy(COLLECTOR.NAME)
        .fetch(COLLECTOR.NAME)
        .toSetOrNull()
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
    return getOrInsertId(name, COLLECTOR.ID, COLLECTOR.NAME, COLLECTOR.SITE_MODULE_ID)
  }

  private fun getStorageLocationId(name: String?): Long? {
    return getId(name, STORAGE_LOCATION.ID, STORAGE_LOCATION.NAME, STORAGE_LOCATION.SITE_MODULE_ID)
  }

  private fun getOrInsertId(
      name: String?,
      idField: TableField<*, Long?>,
      nameField: TableField<*, String?>,
      siteModuleIdField: TableField<*, Long?>? = null,
      extraSetters: (InsertSetMoreStep<out Record>) -> Unit = {}
  ): Long? {
    if (name == null) {
      return null
    }

    val existingId =
        dslContext
            .select(idField)
            .from(idField.table)
            .where(nameField.eq(name))
            .apply { if (siteModuleIdField != null) and(siteModuleIdField.eq(config.siteModuleId)) }
            .fetchOne(idField)
    if (existingId != null) {
      return existingId
    }

    val table = idField.table!!

    return dslContext
        .insertInto(table)
        .set(nameField, name)
        .apply { if (siteModuleIdField != null) set(siteModuleIdField, config.siteModuleId) }
        .apply { extraSetters(this) }
        .returning(idField)
        .fetchOne()
        ?.get(idField)
        ?: throw DataAccessException("Unable to insert new ${table.name.toLowerCase()} $name")
  }

  private fun getId(
      name: String?,
      idField: TableField<*, Long?>,
      nameField: TableField<*, String?>,
      siteModuleIdField: TableField<*, Long?>? = null
  ): Long? {
    if (name == null) {
      return null
    }

    return dslContext
        .select(idField)
        .from(idField.table)
        .where(nameField.eq(name))
        .apply { if (siteModuleIdField != null) and(siteModuleIdField.eq(config.siteModuleId)) }
        .fetchOne(idField)
        ?: throw IllegalArgumentException(
            "Unable to find ${idField.table?.name?.toLowerCase()} $name")
  }
}
