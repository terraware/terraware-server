package com.terraformation.seedbank.db

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.daos.AccessionPhotoDao
import com.terraformation.seedbank.db.tables.pojos.GerminationTest
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTOR
import com.terraformation.seedbank.db.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.seedbank.db.tables.references.COLLECTOR
import com.terraformation.seedbank.db.tables.references.GERMINATION_TEST
import com.terraformation.seedbank.db.tables.references.STORAGE_LOCATION
import com.terraformation.seedbank.db.tables.references.WITHDRAWAL
import com.terraformation.seedbank.model.AccessionActive
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.AccessionModel
import com.terraformation.seedbank.model.AccessionNumberGenerator
import com.terraformation.seedbank.model.AccessionSource
import com.terraformation.seedbank.model.toActiveEnum
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
class AccessionStore(
    private val dslContext: DSLContext,
    private val config: TerrawareServerConfig,
    private val accessionPhotoDao: AccessionPhotoDao,
    private val appDeviceStore: AppDeviceStore,
    private val bagStore: BagStore,
    private val geolocationStore: GeolocationStore,
    private val germinationStore: GerminationStore,
    private val speciesFetcher: SpeciesFetcher,
    private val withdrawalStore: WithdrawalStore,
    private val clock: Clock,
    private val support: StoreSupport,
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
    val bagNumbers = bagStore.fetchBagNumbers(accessionId)
    val deviceInfo = appDeviceStore.fetchById(parentRow[ACCESSION.APP_DEVICE_ID])
    val geolocations = geolocationStore.fetchGeolocations(accessionId)
    val germinationTestTypes = germinationStore.fetchGerminationTestTypes(accessionId)
    val germinationTests = germinationStore.fetchGerminationTests(accessionId)
    val photoFilenames =
        accessionPhotoDao.fetchByAccessionId(accessionId).map { it.filename }.toListOrNull()
    val withdrawals = withdrawalStore.fetchWithdrawals(accessionId)

    val source = if (deviceInfo != null) AccessionSource.SeedCollectorApp else AccessionSource.Web

    return with(ACCESSION) {
      AccessionModel(
          id = accessionId,
          accessionNumber = accessionNumber,
          state = parentRow[STATE_ID]!!,
          source = source,
          species = parentRow[species().NAME],
          family = parentRow[speciesFamily().NAME],
          numberOfTrees = parentRow[TREES_COLLECTED_FROM],
          founderId = parentRow[FOUNDER_ID],
          endangered = parentRow[SPECIES_ENDANGERED_TYPE_ID],
          rare = parentRow[SPECIES_RARE_TYPE_ID],
          fieldNotes = parentRow[FIELD_NOTES],
          collectedDate = parentRow[COLLECTED_DATE],
          receivedDate = parentRow[RECEIVED_DATE],
          primaryCollector = parentRow[collector().NAME],
          secondaryCollectors = secondaryCollectorNames,
          siteLocation = parentRow[COLLECTION_SITE_NAME],
          landowner = parentRow[COLLECTION_SITE_LANDOWNER],
          environmentalNotes = parentRow[ENVIRONMENTAL_NOTES],
          processingStartDate = parentRow[PROCESSING_START_DATE],
          processingMethod = parentRow[PROCESSING_METHOD_ID],
          seedsCounted = parentRow[SEEDS_COUNTED],
          subsetWeightGrams = parentRow[SUBSET_WEIGHT],
          totalWeightGrams = parentRow[TOTAL_WEIGHT],
          subsetCount = parentRow[SUBSET_COUNT],
          estimatedSeedCount = parentRow[EST_SEED_COUNT],
          effectiveSeedCount = parentRow[EFFECTIVE_SEED_COUNT],
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
              accession.deviceInfo?.nullIfEmpty()?.let { appDeviceStore.getOrInsertDevice(it) }

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
                    .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
                    .set(FOUNDER_ID, accession.founderId)
                    .set(SPECIES_ENDANGERED_TYPE_ID, accession.endangered)
                    .set(SPECIES_RARE_TYPE_ID, accession.rare)
                    .set(FIELD_NOTES, accession.fieldNotes)
                    .set(COLLECTED_DATE, accession.collectedDate)
                    .set(RECEIVED_DATE, accession.receivedDate)
                    .set(PRIMARY_COLLECTOR_ID, getCollectorId(accession.primaryCollector))
                    .set(COLLECTION_SITE_NAME, accession.siteLocation)
                    .set(COLLECTION_SITE_LANDOWNER, accession.landowner)
                    .set(ENVIRONMENTAL_NOTES, accession.environmentalNotes)
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
          bagStore.updateBags(accessionId, emptySet(), accession.bagNumbers)
          geolocationStore.updateGeolocations(accessionId, emptySet(), accession.geolocations)
          germinationStore.updateGerminationTestTypes(
              accessionId, emptySet(), accession.germinationTestTypes)
          germinationStore.updateGerminationTests(
              accessionId, emptyList(), accession.germinationTests)
          withdrawalStore.updateWithdrawals(
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
    val todayLocal = LocalDate.now(clock)

    if (accession.storageStartDate?.isAfter(todayLocal) == true) {
      throw IllegalArgumentException("Storage start date may not be in the future")
    }

    dslContext.transaction { _ ->
      if (existing.secondaryCollectors != accession.secondaryCollectors) {
        // TODO: More selective update
        dslContext
            .deleteFrom(ACCESSION_SECONDARY_COLLECTOR)
            .where(ACCESSION_SECONDARY_COLLECTOR.ACCESSION_ID.eq(accessionId))
            .execute()
        insertSecondaryCollectors(accessionId, accession.secondaryCollectors)
      }

      bagStore.updateBags(accessionId, existing.bagNumbers, accession.bagNumbers)
      geolocationStore.updateGeolocations(
          accessionId, existing.geolocations, accession.geolocations)
      germinationStore.updateGerminationTestTypes(
          accessionId, existing.germinationTestTypes, accession.germinationTestTypes)
      germinationStore.updateGerminationTests(
          accessionId, existing.germinationTests, accession.germinationTests)
      withdrawalStore.updateWithdrawals(
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

      val processingStartDate =
          accession.processingStartDate
              ?: existing.processingStartDate ?: accession.calculateProcessingStartDate(clock)
      val collectedDate =
          if (existing.source == AccessionSource.Web) accession.collectedDate
          else existing.collectedDate
      val receivedDate =
          if (existing.source == AccessionSource.Web) accession.receivedDate
          else existing.receivedDate

      val rowsUpdated =
          with(ACCESSION) {
            dslContext
                .update(ACCESSION)
                .set(STATE_ID, stateTransition?.newState ?: existing.state)
                .set(SPECIES_ID, speciesFetcher.getSpeciesId(accession.species))
                .set(SPECIES_FAMILY_ID, speciesFetcher.getSpeciesFamilyId(accession.family))
                .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
                .set(FOUNDER_ID, accession.founderId)
                .set(SPECIES_ENDANGERED_TYPE_ID, accession.endangered)
                .set(SPECIES_RARE_TYPE_ID, accession.rare)
                .set(FIELD_NOTES, accession.fieldNotes)
                .set(COLLECTED_DATE, collectedDate)
                .set(RECEIVED_DATE, receivedDate)
                .set(PRIMARY_COLLECTOR_ID, getCollectorId(accession.primaryCollector))
                .set(PROCESSING_START_DATE, processingStartDate)
                .set(PROCESSING_METHOD_ID, accession.processingMethod)
                .set(SEEDS_COUNTED, accession.seedsCounted)
                .set(SUBSET_WEIGHT, accession.subsetWeightGrams)
                .set(TOTAL_WEIGHT, accession.totalWeightGrams)
                .set(SUBSET_COUNT, accession.subsetCount)
                .set(EST_SEED_COUNT, accession.calculateEstimatedSeedCount())
                .set(EFFECTIVE_SEED_COUNT, accession.calculateEffectiveSeedCount())
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
                .set(ENVIRONMENTAL_NOTES, accession.environmentalNotes)
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

  fun fetchDryingMoveDue(after: TemporalAccessor, until: TemporalAccessor): Map<String, Long> {
    return with(ACCESSION) {
      dslContext
          .select(ID, NUMBER)
          .from(ACCESSION)
          .where(STATE_ID.eq(AccessionState.Drying))
          .and(DRYING_MOVE_DATE.le(LocalDate.ofInstant(until.toInstant(), clock.zone)))
          .and(DRYING_MOVE_DATE.gt(LocalDate.ofInstant(after.toInstant(), clock.zone)))
          .fetch { it[NUMBER]!! to it[ID]!! }
          .toMap()
    }
  }

  fun fetchGerminationTestDue(
      after: TemporalAccessor,
      until: TemporalAccessor
  ): Map<String, GerminationTest> {
    return dslContext
        .select(ACCESSION.NUMBER, GERMINATION_TEST.asterisk())
        .from(ACCESSION)
        .join(GERMINATION_TEST)
        .on(GERMINATION_TEST.ACCESSION_ID.eq(ACCESSION.ID))
        .where(ACCESSION.STATE_ID.`in`(AccessionState.Processing, AccessionState.Processed))
        .and(GERMINATION_TEST.START_DATE.le(LocalDate.ofInstant(until.toInstant(), clock.zone)))
        .and(GERMINATION_TEST.START_DATE.gt(LocalDate.ofInstant(after.toInstant(), clock.zone)))
        .fetch { it[ACCESSION.NUMBER]!! to it.into(GerminationTest::class.java)!! }
        .toMap()
  }

  fun fetchWithdrawalDue(after: TemporalAccessor, until: TemporalAccessor): Map<String, Long> {
    return dslContext
        .selectDistinct(ACCESSION.ID, ACCESSION.NUMBER)
        .from(ACCESSION)
        .join(WITHDRAWAL)
        .on(WITHDRAWAL.ACCESSION_ID.eq(ACCESSION.ID))
        .where(WITHDRAWAL.DATE.le(LocalDate.ofInstant(until.toInstant(), clock.zone)))
        .and(WITHDRAWAL.DATE.gt(LocalDate.ofInstant(after.toInstant(), clock.zone)))
        .fetch { it[ACCESSION.NUMBER]!! to it[ACCESSION.ID]!! }
        .toMap()
  }
}
