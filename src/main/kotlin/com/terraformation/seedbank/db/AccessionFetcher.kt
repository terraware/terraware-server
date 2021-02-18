package com.terraformation.seedbank.db

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTOR
import com.terraformation.seedbank.db.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.seedbank.db.tables.references.COLLECTOR
import com.terraformation.seedbank.db.tables.references.STORAGE_LOCATION
import com.terraformation.seedbank.model.AccessionActive
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.AccessionModel
import com.terraformation.seedbank.model.AccessionNumberGenerator
import com.terraformation.seedbank.model.toActiveEnum
import com.terraformation.seedbank.photo.PhotoRepository
import com.terraformation.seedbank.services.debugWithTiming
import com.terraformation.seedbank.services.perClassLogger
import com.terraformation.seedbank.services.toInstant
import com.terraformation.seedbank.services.toListOrNull
import com.terraformation.seedbank.services.toSetOrNull
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.conf.ParamType
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class AccessionFetcher(
    private val dslContext: DSLContext,
    private val config: TerrawareServerConfig,
    private val appDeviceFetcher: AppDeviceFetcher,
    private val bagFetcher: BagFetcher,
    private val collectionEventFetcher: CollectionEventFetcher,
    private val germinationFetcher: GerminationFetcher,
    private val photoRepository: PhotoRepository,
    private val speciesFetcher: SpeciesFetcher,
    private val withdrawalFetcher: WithdrawalFetcher,
    private val clock: Clock,
    private val support: FetcherSupport,
) {
  companion object {
    /** Number of times to try generating a unique accession number before giving up. */
    private const val ACCESSION_NUMBER_RETRIES = 10
  }

  var accessionNumberGenerator = AccessionNumberGenerator()

  private val log = perClassLogger()

  /**
   * Looks up the ID of an accession with the given accession number. Returns null if the accession
   * number does not exist.
   */
  fun getIdByNumber(accessionNumber: String): Long? {
    return dslContext
        .select(ACCESSION.ID)
        .from(ACCESSION)
        .where(ACCESSION.NUMBER.eq(accessionNumber))
        .fetchOne(ACCESSION.ID)
  }

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
    val deviceInfo = appDeviceFetcher.fetchById(parentRow[ACCESSION.APP_DEVICE_ID])
    val geolocations = collectionEventFetcher.fetchGeolocations(accessionId)
    val germinationTestTypes = germinationFetcher.fetchGerminationTestTypes(accessionId)
    val germinationTests = germinationFetcher.fetchGerminationTests(accessionId)
    val photoFilenames = photoRepository.listPhotos(accessionId).map { it.filename }.toListOrNull()
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
          photoFilenames = photoFilenames,
          geolocations = geolocations,
          germinationTestTypes = germinationTestTypes,
          germinationTests = germinationTests,
          withdrawals = withdrawals,
          cutTestSeedsFilled = parentRow[CUT_TEST_SEEDS_FILLED],
          cutTestSeedsEmpty = parentRow[CUT_TEST_SEEDS_EMPTY],
          cutTestSeedsCompromised = parentRow[CUT_TEST_SEEDS_COMPROMISED],
          latestGerminationTestDate = parentRow[LATEST_GERMINATION_RECORDING_DATE],
          latestViabilityPercent = parentRow[LATEST_VIABILITY_PERCENT],
          totalViabilityPercent = parentRow[TOTAL_VIABILITY_PERCENT],
          deviceInfo = deviceInfo,
          seedsRemaining = parentRow[SEEDS_REMAINING],
      )
    }
  }

  fun create(accession: AccessionFields): AccessionModel {
    var attemptsRemaining = ACCESSION_NUMBER_RETRIES

    while (attemptsRemaining-- > 0) {
      val accessionNumber = accessionNumberGenerator.generateAccessionNumber()

      try {
        dslContext.transaction { _ ->
          val appDeviceId =
              accession.deviceInfo?.nullIfEmpty()?.let { appDeviceFetcher.getOrInsertDevice(it) }

          val accessionId =
              with(ACCESSION) {
                dslContext
                    .insertInto(ACCESSION)
                    .set(NUMBER, accessionNumber)
                    .set(SITE_MODULE_ID, config.siteModuleId)
                    .set(CREATED_TIME, clock.instant())
                    .set(STATE_ID, AccessionState.Pending)
                    .set(SPECIES_ID, speciesFetcher.getSpeciesId(accession.species))
                    .set(SPECIES_FAMILY_ID, speciesFetcher.getSpeciesFamilyId(accession.family))
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
                    .set(CUT_TEST_SEEDS_COMPROMISED, accession.cutTestSeedsCompromised)
                    .set(CUT_TEST_SEEDS_EMPTY, accession.cutTestSeedsEmpty)
                    .set(CUT_TEST_SEEDS_FILLED, accession.cutTestSeedsFilled)
                    .set(TOTAL_VIABILITY_PERCENT, accession.calculateTotalViabilityPercent())
                    .set(LATEST_VIABILITY_PERCENT, accession.calculateLatestViabilityPercent())
                    .set(
                        LATEST_GERMINATION_RECORDING_DATE,
                        accession.calculateLatestGerminationRecordingDate())
                    .set(APP_DEVICE_ID, appDeviceId)
                    .returning(ID)
                    .fetchOne()
                    ?.get(ID)!!
              }

          with(ACCESSION_STATE_HISTORY) {
            dslContext
                .insertInto(ACCESSION_STATE_HISTORY)
                .set(ACCESSION_ID, accessionId)
                .set(REASON, "Accession created")
                .set(NEW_STATE_ID, AccessionState.Pending)
                .set(UPDATED_TIME, clock.instant())
                .execute()
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

      val stateTransition = existing.getStateTransition(accession, clock)
      if (stateTransition != null) {
        log.info(
            "Accession $accessionNumber transitioning from ${existing.state} to " +
                "${stateTransition.newState}: ${stateTransition.reason}")

        with(ACCESSION_STATE_HISTORY) {
          dslContext
              .insertInto(ACCESSION_STATE_HISTORY)
              .set(ACCESSION_ID, accessionId)
              .set(NEW_STATE_ID, stateTransition.newState)
              .set(OLD_STATE_ID, existing.state)
              .set(REASON, stateTransition.reason)
              .set(UPDATED_TIME, clock.instant())
              .execute()
        }
      }

      val rowsUpdated =
          with(ACCESSION) {
            dslContext
                .update(ACCESSION)
                .set(STATE_ID, stateTransition?.newState ?: existing.state)
                .set(SPECIES_ID, speciesFetcher.getSpeciesId(accession.species))
                .set(SPECIES_FAMILY_ID, speciesFetcher.getSpeciesFamilyId(accession.family))
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
                .set(EST_SEED_COUNT, accession.calculateEstimatedSeedCount())
                .set(TARGET_STORAGE_CONDITION, accession.targetStorageCondition)
                .set(CUT_TEST_SEEDS_FILLED, accession.cutTestSeedsFilled)
                .set(CUT_TEST_SEEDS_EMPTY, accession.cutTestSeedsEmpty)
                .set(CUT_TEST_SEEDS_COMPROMISED, accession.cutTestSeedsCompromised)
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
                .set(
                    LATEST_GERMINATION_RECORDING_DATE,
                    accession.calculateLatestGerminationRecordingDate())
                .set(LATEST_VIABILITY_PERCENT, accession.calculateLatestViabilityPercent())
                .set(TOTAL_VIABILITY_PERCENT, accession.calculateTotalViabilityPercent())
                .set(SEEDS_REMAINING, accession.calculateSeedsRemaining())
                .where(NUMBER.eq(accessionNumber))
                .and(SITE_MODULE_ID.eq(config.siteModuleId))
                .execute()
          }

      if (rowsUpdated != 1) {
        log.error("Accession $accessionNumber exists in database but update failed")
        throw DataAccessException("Unable to update accession $accessionNumber")
      }
    }

    return true
  }

  /**
   * Returns a list of accessions for which the scheduled date for a time-based state transition has
   * arrived or passed.
   */
  fun fetchTimedStateTransitionCandidates(): List<AccessionModel> {
    val today = LocalDate.now(clock)
    val twoWeeksAgo = today.minusDays(14)

    return with(ACCESSION) {
      dslContext
          .select(NUMBER)
          .from(ACCESSION)
          .where(
              STATE_ID
                  .eq(AccessionState.Processing)
                  .and(PROCESSING_START_DATE.le(twoWeeksAgo).or(DRYING_START_DATE.le(today))))
          .or(STATE_ID.eq(AccessionState.Processed).and(DRYING_START_DATE.le(today)))
          .or(
              STATE_ID
                  .eq(AccessionState.Drying)
                  .and(STORAGE_START_DATE.le(today).or(DRYING_END_DATE.le(today))))
          .or(STATE_ID.eq(AccessionState.Dried).and(STORAGE_START_DATE.le(today)))
          .fetch(NUMBER)
          .mapNotNull { accessionNumber ->
            // This is an N+1 query which isn't ideal but we are going to be processing these one
            // at a time anyway so optimizing this to a single SELECT wouldn't help much.
            fetchByNumber(accessionNumber!!)
          }
    }
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

  private fun getCollectorId(name: String?): Long? {
    return support.getOrInsertId(name, COLLECTOR.ID, COLLECTOR.NAME, COLLECTOR.SITE_MODULE_ID)
  }

  private fun getStorageLocationId(name: String?): Long? {
    return support.getId(
        name, STORAGE_LOCATION.ID, STORAGE_LOCATION.NAME, STORAGE_LOCATION.SITE_MODULE_ID)
  }

  /**
   * Returns the number of accessions that were active as of a particular time.
   *
   * Assumptions that will cause this to break if they become false later on:
   *
   * - Accessions can't become active again once they enter an inactive state.
   * - [asOf] will be a fairly recent time. (The correct result will be returned even if not, but
   * the query will be inefficient.)
   */
  fun countActive(asOf: TemporalAccessor): Int {
    val statesByActive = AccessionState.values().groupBy { it.toActiveEnum() }

    val query =
        dslContext
            .select(DSL.count())
            .from(ACCESSION)
            .where(ACCESSION.CREATED_TIME.le(asOf.toInstant()))
            .and(
                ACCESSION
                    .STATE_ID
                    .`in`(statesByActive[AccessionActive.Active])
                    .orNotExists(
                        dslContext
                            .selectOne()
                            .from(ACCESSION_STATE_HISTORY)
                            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(ACCESSION.ID))
                            .and(ACCESSION_STATE_HISTORY.UPDATED_TIME.le(asOf.toInstant()))
                            .and(
                                ACCESSION_STATE_HISTORY.NEW_STATE_ID.`in`(
                                    statesByActive[AccessionActive.Inactive]))))

    log.debug("Active accessions query ${query.getSQL(ParamType.INLINED)}")

    return log.debugWithTiming("Active accessions query") { query.fetchOne()?.value1() ?: 0 }
  }

  /**
   * Returns the number of accessions that entered a state during a time period and are still in
   * that state now.
   *
   * @param sinceAfter Only count accessions that changed to the state at or after this time.
   * @param sinceBefore Only count accessions that changed to the state at or before this time.
   */
  fun countInState(
      state: AccessionState,
      sinceAfter: TemporalAccessor? = null,
      sinceBefore: TemporalAccessor? = null,
  ): Int {
    val query =
        dslContext
            .select(DSL.count())
            .from(
                DSL
                    .selectDistinct(ACCESSION.ID)
                    .from(ACCESSION_STATE_HISTORY)
                    .join(ACCESSION)
                    .on(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(ACCESSION.ID))
                    .where(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(state))
                    .and(ACCESSION.STATE_ID.eq(state))
                    .apply {
                      if (sinceAfter != null) {
                        and(ACCESSION_STATE_HISTORY.UPDATED_TIME.ge(sinceAfter.toInstant()))
                      }
                    }
                    .apply {
                      if (sinceBefore != null) {
                        and(ACCESSION_STATE_HISTORY.UPDATED_TIME.le(sinceBefore.toInstant()))
                      }
                    })

    log.debug("Accession state count query: ${query.getSQL(ParamType.INLINED)}")

    return log.debugWithTiming("Accession state count with time bounds") {
      query.fetchOne()?.value1() ?: 0
    }
  }

  /** Returns the number of accessions currently in a given state. */
  fun countInState(state: AccessionState): Int {
    val query = dslContext.select(DSL.count()).from(ACCESSION).where(ACCESSION.STATE_ID.eq(state))

    log.debug("Accession state count query: ${query.getSQL(ParamType.INLINED)}")

    return log.debugWithTiming("Accession state count query") { query.fetchOne()?.value1() ?: 0 }
  }
}
