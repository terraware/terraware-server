package com.terraformation.seedbank.db

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.sequences.ACCESSION_NUMBER_SEQ
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
import com.terraformation.seedbank.model.AccessionSource
import com.terraformation.seedbank.model.GerminationTestWithdrawal
import com.terraformation.seedbank.model.toActiveEnum
import com.terraformation.seedbank.services.debugWithTiming
import com.terraformation.seedbank.services.perClassLogger
import com.terraformation.seedbank.services.toInstant
import com.terraformation.seedbank.services.toListOrNull
import com.terraformation.seedbank.services.toSetOrNull
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
    private val speciesStore: SpeciesStore,
    private val withdrawalStore: WithdrawalStore,
    private val clock: Clock,
    private val support: StoreSupport,
) {
  companion object {
    /** Number of times to try generating a unique accession number before giving up. */
    private const val ACCESSION_NUMBER_RETRIES = 10
  }

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
          accessionNumber = accessionNumber,
          bagNumbers = bagNumbers,
          collectedDate = parentRow[COLLECTED_DATE],
          cutTestSeedsCompromised = parentRow[CUT_TEST_SEEDS_COMPROMISED],
          cutTestSeedsEmpty = parentRow[CUT_TEST_SEEDS_EMPTY],
          cutTestSeedsFilled = parentRow[CUT_TEST_SEEDS_FILLED],
          deviceInfo = deviceInfo,
          dryingEndDate = parentRow[DRYING_END_DATE],
          dryingMoveDate = parentRow[DRYING_MOVE_DATE],
          dryingStartDate = parentRow[DRYING_START_DATE],
          effectiveSeedCount = parentRow[EFFECTIVE_SEED_COUNT],
          endangered = parentRow[SPECIES_ENDANGERED_TYPE_ID],
          environmentalNotes = parentRow[ENVIRONMENTAL_NOTES],
          estimatedSeedCount = parentRow[EST_SEED_COUNT],
          family = parentRow[speciesFamily().NAME],
          fieldNotes = parentRow[FIELD_NOTES],
          founderId = parentRow[FOUNDER_ID],
          geolocations = geolocations,
          germinationTestTypes = germinationTestTypes,
          germinationTests = germinationTests,
          id = accessionId,
          landowner = parentRow[COLLECTION_SITE_LANDOWNER],
          latestGerminationTestDate = parentRow[LATEST_GERMINATION_RECORDING_DATE],
          latestViabilityPercent = parentRow[LATEST_VIABILITY_PERCENT],
          numberOfTrees = parentRow[TREES_COLLECTED_FROM],
          nurseryStartDate = parentRow[NURSERY_START_DATE],
          photoFilenames = photoFilenames,
          primaryCollector = parentRow[collector().NAME],
          processingMethod = parentRow[PROCESSING_METHOD_ID],
          processingNotes = parentRow[PROCESSING_NOTES],
          processingStaffResponsible = parentRow[PROCESSING_STAFF_RESPONSIBLE],
          processingStartDate = parentRow[PROCESSING_START_DATE],
          rare = parentRow[SPECIES_RARE_TYPE_ID],
          receivedDate = parentRow[RECEIVED_DATE],
          secondaryCollectors = secondaryCollectorNames,
          seedsCounted = parentRow[SEEDS_COUNTED],
          seedsRemaining = parentRow[SEEDS_REMAINING],
          siteLocation = parentRow[COLLECTION_SITE_NAME],
          source = source,
          sourcePlantOrigin = parentRow[SOURCE_PLANT_ORIGIN_ID],
          species = parentRow[species().NAME],
          speciesId = parentRow[SPECIES_ID],
          state = parentRow[STATE_ID]!!,
          storageCondition = parentRow[storageLocation().CONDITION_ID],
          storageLocation = parentRow[storageLocation().NAME],
          storageNotes = parentRow[STORAGE_NOTES],
          storagePackets = parentRow[STORAGE_PACKETS],
          storageStaffResponsible = parentRow[STORAGE_STAFF_RESPONSIBLE],
          storageStartDate = parentRow[STORAGE_START_DATE],
          subsetCount = parentRow[SUBSET_COUNT],
          subsetWeightGrams = parentRow[SUBSET_WEIGHT],
          targetStorageCondition = parentRow[TARGET_STORAGE_CONDITION],
          totalViabilityPercent = parentRow[TOTAL_VIABILITY_PERCENT],
          totalWeightGrams = parentRow[TOTAL_WEIGHT],
          withdrawals = withdrawals,
      )
    }
  }

  fun create(accession: AccessionFields): AccessionModel {
    var attemptsRemaining = ACCESSION_NUMBER_RETRIES

    while (attemptsRemaining-- > 0) {
      val accessionNumber = generateAccessionNumber()

      try {
        dslContext.transaction { _ ->
          val appDeviceId =
              accession.deviceInfo?.nullIfEmpty()?.let { appDeviceStore.getOrInsertDevice(it) }

          val accessionId =
              with(ACCESSION) {
                dslContext
                    .insertInto(ACCESSION)
                    .set(APP_DEVICE_ID, appDeviceId)
                    .set(COLLECTED_DATE, accession.collectedDate)
                    .set(COLLECTION_SITE_LANDOWNER, accession.landowner)
                    .set(COLLECTION_SITE_NAME, accession.siteLocation)
                    .set(CREATED_TIME, clock.instant())
                    .set(CUT_TEST_SEEDS_COMPROMISED, accession.cutTestSeedsCompromised)
                    .set(CUT_TEST_SEEDS_EMPTY, accession.cutTestSeedsEmpty)
                    .set(CUT_TEST_SEEDS_FILLED, accession.cutTestSeedsFilled)
                    .set(ENVIRONMENTAL_NOTES, accession.environmentalNotes)
                    .set(FIELD_NOTES, accession.fieldNotes)
                    .set(FOUNDER_ID, accession.founderId)
                    .set(
                        LATEST_GERMINATION_RECORDING_DATE,
                        accession.calculateLatestGerminationRecordingDate())
                    .set(LATEST_VIABILITY_PERCENT, accession.calculateLatestViabilityPercent())
                    .set(NUMBER, accessionNumber)
                    .set(NURSERY_START_DATE, accession.nurseryStartDate)
                    .set(PRIMARY_COLLECTOR_ID, getCollectorId(accession.primaryCollector))
                    .set(RECEIVED_DATE, accession.receivedDate)
                    .set(SITE_MODULE_ID, config.siteModuleId)
                    .set(SOURCE_PLANT_ORIGIN_ID, accession.sourcePlantOrigin)
                    .set(SPECIES_ENDANGERED_TYPE_ID, accession.endangered)
                    .set(SPECIES_FAMILY_ID, speciesStore.getSpeciesFamilyId(accession.family))
                    .set(SPECIES_ID, speciesStore.getSpeciesId(accession.species))
                    .set(SPECIES_RARE_TYPE_ID, accession.rare)
                    .set(STATE_ID, AccessionState.Pending)
                    .set(STORAGE_LOCATION_ID, getStorageLocationId(accession.storageLocation))
                    .set(STORAGE_NOTES, accession.storageNotes)
                    .set(STORAGE_PACKETS, accession.storagePackets)
                    .set(STORAGE_STAFF_RESPONSIBLE, accession.storageStaffResponsible)
                    .set(STORAGE_START_DATE, accession.storageStartDate)
                    .set(TOTAL_VIABILITY_PERCENT, accession.calculateTotalViabilityPercent())
                    .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
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

      val manualWithdrawals =
          accession.withdrawals?.filter { it.purpose != WithdrawalPurpose.GerminationTesting }
              ?: emptyList()
      val desiredWithdrawals = manualWithdrawals + generateGerminationTestWithdrawals(accessionId)
      withdrawalStore.updateWithdrawals(
          accessionId, accession, existing.withdrawals, desiredWithdrawals)

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
                .set(COLLECTED_DATE, collectedDate)
                .set(COLLECTION_SITE_LANDOWNER, accession.landowner)
                .set(COLLECTION_SITE_NAME, accession.siteLocation)
                .set(CUT_TEST_SEEDS_COMPROMISED, accession.cutTestSeedsCompromised)
                .set(CUT_TEST_SEEDS_EMPTY, accession.cutTestSeedsEmpty)
                .set(CUT_TEST_SEEDS_FILLED, accession.cutTestSeedsFilled)
                .set(DRYING_END_DATE, accession.dryingEndDate)
                .set(DRYING_MOVE_DATE, accession.dryingMoveDate)
                .set(DRYING_START_DATE, accession.dryingStartDate)
                .set(EFFECTIVE_SEED_COUNT, accession.calculateEffectiveSeedCount())
                .set(ENVIRONMENTAL_NOTES, accession.environmentalNotes)
                .set(EST_SEED_COUNT, accession.calculateEstimatedSeedCount())
                .set(FIELD_NOTES, accession.fieldNotes)
                .set(FOUNDER_ID, accession.founderId)
                .set(
                    LATEST_GERMINATION_RECORDING_DATE,
                    accession.calculateLatestGerminationRecordingDate())
                .set(LATEST_VIABILITY_PERCENT, accession.calculateLatestViabilityPercent())
                .set(NURSERY_START_DATE, accession.nurseryStartDate)
                .set(PRIMARY_COLLECTOR_ID, getCollectorId(accession.primaryCollector))
                .set(PROCESSING_METHOD_ID, accession.processingMethod)
                .set(PROCESSING_NOTES, accession.processingNotes)
                .set(PROCESSING_STAFF_RESPONSIBLE, accession.processingStaffResponsible)
                .set(PROCESSING_START_DATE, processingStartDate)
                .set(RECEIVED_DATE, receivedDate)
                .set(SEEDS_COUNTED, accession.seedsCounted)
                .set(SEEDS_REMAINING, accession.calculateSeedsRemaining())
                .set(SOURCE_PLANT_ORIGIN_ID, accession.sourcePlantOrigin)
                .set(SPECIES_ENDANGERED_TYPE_ID, accession.endangered)
                .set(SPECIES_FAMILY_ID, speciesStore.getSpeciesFamilyId(accession.family))
                .set(SPECIES_ID, speciesStore.getSpeciesId(accession.species))
                .set(SPECIES_RARE_TYPE_ID, accession.rare)
                .set(STATE_ID, stateTransition?.newState ?: existing.state)
                .set(STORAGE_LOCATION_ID, getStorageLocationId(accession.storageLocation))
                .set(STORAGE_NOTES, accession.storageNotes)
                .set(STORAGE_PACKETS, accession.storagePackets)
                .set(STORAGE_STAFF_RESPONSIBLE, accession.storageStaffResponsible)
                .set(STORAGE_START_DATE, accession.storageStartDate)
                .set(SUBSET_COUNT, accession.subsetCount)
                .set(SUBSET_WEIGHT, accession.subsetWeightGrams)
                .set(TARGET_STORAGE_CONDITION, accession.targetStorageCondition)
                .set(TOTAL_VIABILITY_PERCENT, accession.calculateTotalViabilityPercent())
                .set(TOTAL_WEIGHT, accession.totalWeightGrams)
                .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
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
   * Updates an accession and returns the modified accession data including any computed field
   * values.
   *
   * @return null if the accession didn't exist.
   */
  fun updateAndFetch(
      accession: AccessionFields,
      accessionNumber: String = accession.accessionNumber!!
  ): AccessionModel {
    val updated =
        if (update(accessionNumber, accession)) {
          fetchByNumber(accessionNumber)
        } else {
          null
        }

    return updated ?: throw AccessionNotFoundException(accessionNumber)
  }

  /**
   * Updates information about a species. If the new species name is already in use, updates any
   * existing accessions that use the old name to use the existing species ID for the new name.
   *
   * @return The ID of the existing species with the requested name if the name was already in use
   * or null if not.
   */
  fun updateSpecies(speciesId: Long, name: String): Long? {
    try {
      dslContext.transaction { _ -> speciesStore.updateSpecies(speciesId, name) }

      log.info("Renamed species $speciesId to $name")

      return null
    } catch (e: DuplicateKeyException) {
      val existingSpeciesId = speciesStore.getSpeciesId(name)!!

      dslContext.transaction { _ ->
        val rowsUpdated =
            dslContext
                .update(ACCESSION)
                .set(ACCESSION.SPECIES_ID, existingSpeciesId)
                .where(ACCESSION.SPECIES_ID.eq(speciesId))
                .execute()
        speciesStore.deleteSpecies(speciesId)

        log.info(
            "Updated $rowsUpdated accession(s) to change species ID $speciesId to " +
                "$existingSpeciesId with name $name")
      }

      return existingSpeciesId
    } catch (e: DataAccessException) {
      if (e.cause is SpeciesNotFoundException) {
        throw e.cause!!
      } else {
        throw e
      }
    }
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

  /**
   * Returns the next unused accession number.
   *
   * Accession numbers are of the form YYYYMMDDXXX where XXX is a numeric suffix of three or more
   * digits that starts at 000 for the first accession created on a particular date. The desired
   * behavior is for the suffix to represent the order in which accessions were received, so ideally
   * we want to avoid gaps or out-of-order values, though it's fine for that to be best-effort.
   *
   * The implementation uses a database sequence. The sequence's values follow the same pattern as
   * the accession numbers, but the suffix is always 10 digits; it is rendered as a 3-or-more-digit
   * value by this method.
   *
   * If the date part of the sequence value doesn't match the current date, this method resets the
   * sequence to the zero suffix for the current date.
   *
   * Note that there is a bit of a race condition if multiple seedbank-server instances happen to
   * allocate their first accession of a given day at the same time; they might both reset the
   * sequence. To guard against that, [create] will retry a few times if it gets a unique constraint
   * violation on the accession number.
   */
  private fun generateAccessionNumber(): String {
    val suffixMultiplier = 10000000000L
    val todayAsLong = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE).toLong()

    val sequenceValue =
        dslContext.select(ACCESSION_NUMBER_SEQ.nextval()).fetchOne(ACCESSION_NUMBER_SEQ.nextval())!!
    val datePart = sequenceValue / suffixMultiplier
    val suffixPart = sequenceValue.rem(suffixMultiplier)

    val suffix =
        if (todayAsLong != datePart) {
          val firstValueForToday = todayAsLong * suffixMultiplier
          dslContext
              .alterSequence(ACCESSION_NUMBER_SEQ)
              .restartWith(firstValueForToday + 1)
              .execute()
          log.info("Resetting accession sequence to $firstValueForToday")
          0
        } else {
          suffixPart
        }

    return "%08d%03d".format(todayAsLong, suffix)
  }

  private fun generateGerminationTestWithdrawals(
      accessionId: Long
  ): List<GerminationTestWithdrawal> {
    val tests = germinationStore.fetchGerminationTests(accessionId) ?: return emptyList()
    val withdrawalsByTestId =
        withdrawalStore
            .fetchWithdrawals(accessionId)
            ?.filter { it.germinationTestId != null }
            ?.associateBy { it.germinationTestId }
            ?: emptyMap()

    return tests.mapNotNull { test ->
      val existingWithdrawalId = withdrawalsByTestId[test.id]?.id
      test.toWithdrawal(existingWithdrawalId, clock)
    }
  }
}
