package com.terraformation.backend.db

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.sequences.ACCESSION_NUMBER_SEQ
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.pojos.GerminationTestsRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_SECONDARY_COLLECTORS
import com.terraformation.backend.db.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.model.AccessionActive
import com.terraformation.backend.model.AccessionModel
import com.terraformation.backend.model.AccessionSource
import com.terraformation.backend.model.GerminationTestModel
import com.terraformation.backend.model.SeedQuantityModel
import com.terraformation.backend.model.toActiveEnum
import com.terraformation.backend.services.debugWithTiming
import com.terraformation.backend.services.perClassLogger
import com.terraformation.backend.services.toInstant
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
    private val accessionPhotosDao: AccessionPhotosDao,
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
        .select(ACCESSIONS.ID)
        .from(ACCESSIONS)
        .where(ACCESSIONS.NUMBER.eq(accessionNumber))
        .fetchOne(ACCESSIONS.ID)
  }

  fun fetchByNumber(accessionNumber: String): AccessionModel? {
    // First, fetch all the values that are either directly on the accession table or are in other
    // tables such that there is at most one value for a given accession (N:1 relation).
    val parentRow =
        dslContext
            .select(
                ACCESSIONS.asterisk(),
                ACCESSIONS.collectors().NAME,
                ACCESSIONS.species().NAME,
                ACCESSIONS.speciesFamilies().NAME,
                ACCESSIONS.STATE_ID,
                ACCESSIONS.storageLocations().NAME,
                ACCESSIONS.storageLocations().CONDITION_ID,
                ACCESSIONS.TARGET_STORAGE_CONDITION,
                ACCESSIONS.PROCESSING_METHOD_ID,
                ACCESSIONS.PROCESSING_STAFF_RESPONSIBLE,
            )
            .from(ACCESSIONS)
            .where(ACCESSIONS.NUMBER.eq(accessionNumber))
            .and(ACCESSIONS.FACILITY_ID.eq(config.facilityId))
            .fetchOne()
            ?: return null

    // Now populate all the items that there can be many of per accession.
    val accessionId = parentRow[ACCESSIONS.ID]!!

    val secondaryCollectorNames = fetchSecondaryCollectorNames(accessionId)
    val bagNumbers = bagStore.fetchBagNumbers(accessionId)
    val deviceInfo = appDeviceStore.fetchById(parentRow[ACCESSIONS.APP_DEVICE_ID])
    val geolocations = geolocationStore.fetchGeolocations(accessionId)
    val germinationTestTypes = germinationStore.fetchGerminationTestTypes(accessionId)
    val germinationTests = germinationStore.fetchGerminationTests(accessionId)
    val photoFilenames =
        accessionPhotosDao.fetchByAccessionId(accessionId).mapNotNull { it.filename }
    val withdrawals = withdrawalStore.fetchWithdrawals(accessionId)

    val source = if (deviceInfo != null) AccessionSource.SeedCollectorApp else AccessionSource.Web

    return with(ACCESSIONS) {
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
          endangered = parentRow[SPECIES_ENDANGERED_TYPE_ID],
          environmentalNotes = parentRow[ENVIRONMENTAL_NOTES],
          estimatedSeedCount = parentRow[EST_SEED_COUNT],
          family = parentRow[speciesFamilies().NAME],
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
          primaryCollector = parentRow[collectors().NAME],
          processingMethod = parentRow[PROCESSING_METHOD_ID],
          processingNotes = parentRow[PROCESSING_NOTES],
          processingStaffResponsible = parentRow[PROCESSING_STAFF_RESPONSIBLE],
          processingStartDate = parentRow[PROCESSING_START_DATE],
          rare = parentRow[SPECIES_RARE_TYPE_ID],
          receivedDate = parentRow[RECEIVED_DATE],
          remaining =
              SeedQuantityModel.of(parentRow[REMAINING_QUANTITY], parentRow[REMAINING_UNITS_ID]),
          secondaryCollectors = secondaryCollectorNames,
          siteLocation = parentRow[COLLECTION_SITE_NAME],
          source = source,
          sourcePlantOrigin = parentRow[SOURCE_PLANT_ORIGIN_ID],
          species = parentRow[species().NAME],
          speciesId = parentRow[SPECIES_ID],
          state = parentRow[STATE_ID]!!,
          storageCondition = parentRow[storageLocations().CONDITION_ID],
          storageLocation = parentRow[storageLocations().NAME],
          storageNotes = parentRow[STORAGE_NOTES],
          storagePackets = parentRow[STORAGE_PACKETS],
          storageStaffResponsible = parentRow[STORAGE_STAFF_RESPONSIBLE],
          storageStartDate = parentRow[STORAGE_START_DATE],
          subsetCount = parentRow[SUBSET_COUNT],
          subsetWeightQuantity =
              SeedQuantityModel.of(
                  parentRow[SUBSET_WEIGHT_QUANTITY], parentRow[SUBSET_WEIGHT_UNITS_ID]),
          targetStorageCondition = parentRow[TARGET_STORAGE_CONDITION],
          total = SeedQuantityModel.of(parentRow[TOTAL_QUANTITY], parentRow[TOTAL_UNITS_ID]),
          totalViabilityPercent = parentRow[TOTAL_VIABILITY_PERCENT],
          withdrawals = withdrawals,
      )
    }
  }

  fun create(accession: AccessionModel): AccessionModel {
    var attemptsRemaining = ACCESSION_NUMBER_RETRIES

    while (attemptsRemaining-- > 0) {
      val accessionNumber = generateAccessionNumber()

      try {
        dslContext.transaction { _ ->
          val appDeviceId =
              accession.deviceInfo?.nullIfEmpty()?.let { appDeviceStore.getOrInsertDevice(it) }

          val accessionId =
              with(ACCESSIONS) {
                dslContext
                    .insertInto(ACCESSIONS)
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
                    .set(FACILITY_ID, config.facilityId)
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
          withdrawalStore.updateWithdrawals(accessionId, emptyList(), accession.withdrawals)
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

  fun update(accessionNumber: String, updated: AccessionModel): Boolean {
    val existing = fetchByNumber(accessionNumber) ?: return false
    val accessionId = existing.id ?: return false
    val accession = updated.withCalculatedValues(clock, existing)
    val todayLocal = LocalDate.now(clock)

    if (accession.storageStartDate?.isAfter(todayLocal) == true) {
      throw IllegalArgumentException("Storage start date may not be in the future")
    }

    if (accession.subsetWeightQuantity?.units == SeedQuantityUnits.Seeds) {
      throw IllegalArgumentException("Subset weight must be a weight measurement, not a seed count")
    }

    dslContext.transaction { _ ->
      if (existing.secondaryCollectors != accession.secondaryCollectors) {
        // TODO: More selective update
        dslContext
            .deleteFrom(ACCESSION_SECONDARY_COLLECTORS)
            .where(ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID.eq(accessionId))
            .execute()
        insertSecondaryCollectors(accessionId, accession.secondaryCollectors)
      }

      val existingTests: MutableList<GerminationTestModel> =
          existing.germinationTests.toMutableList()
      val withdrawals =
          accession.withdrawals.map { withdrawal ->
            withdrawal.germinationTest?.let { germinationTest ->
              if (germinationTest.id == null) {
                val insertedTest =
                    germinationStore.insertGerminationTest(accessionId, germinationTest)
                existingTests.add(insertedTest)
                withdrawal.copy(germinationTest = insertedTest, germinationTestId = insertedTest.id)
              } else {
                withdrawal
              }
            }
                ?: withdrawal
          }

      val germinationTests = withdrawals.mapNotNull { it.germinationTest }

      bagStore.updateBags(accessionId, existing.bagNumbers, accession.bagNumbers)
      geolocationStore.updateGeolocations(
          accessionId, existing.geolocations, accession.geolocations)
      germinationStore.updateGerminationTestTypes(
          accessionId, existing.germinationTestTypes, accession.germinationTestTypes)
      germinationStore.updateGerminationTests(accessionId, existingTests, germinationTests)
      withdrawalStore.updateWithdrawals(accessionId, existing.withdrawals, withdrawals)

      if (existing.state != accession.state) {
        existing.getStateTransition(accession, clock)?.let { stateTransition ->
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
      }

      val rowsUpdated =
          with(ACCESSIONS) {
            dslContext
                .update(ACCESSIONS)
                .set(COLLECTED_DATE, accession.collectedDate)
                .set(COLLECTION_SITE_LANDOWNER, accession.landowner)
                .set(COLLECTION_SITE_NAME, accession.siteLocation)
                .set(CUT_TEST_SEEDS_COMPROMISED, accession.cutTestSeedsCompromised)
                .set(CUT_TEST_SEEDS_EMPTY, accession.cutTestSeedsEmpty)
                .set(CUT_TEST_SEEDS_FILLED, accession.cutTestSeedsFilled)
                .set(DRYING_END_DATE, accession.dryingEndDate)
                .set(DRYING_MOVE_DATE, accession.dryingMoveDate)
                .set(DRYING_START_DATE, accession.dryingStartDate)
                .set(ENVIRONMENTAL_NOTES, accession.environmentalNotes)
                .set(EST_SEED_COUNT, accession.estimatedSeedCount)
                .set(FIELD_NOTES, accession.fieldNotes)
                .set(FOUNDER_ID, accession.founderId)
                .set(LATEST_GERMINATION_RECORDING_DATE, accession.latestGerminationTestDate)
                .set(LATEST_VIABILITY_PERCENT, accession.latestViabilityPercent)
                .set(NURSERY_START_DATE, accession.nurseryStartDate)
                .set(PRIMARY_COLLECTOR_ID, getCollectorId(accession.primaryCollector))
                .set(PROCESSING_METHOD_ID, accession.processingMethod)
                .set(PROCESSING_NOTES, accession.processingNotes)
                .set(PROCESSING_STAFF_RESPONSIBLE, accession.processingStaffResponsible)
                .set(PROCESSING_START_DATE, accession.processingStartDate)
                .set(RECEIVED_DATE, accession.receivedDate)
                .set(REMAINING_GRAMS, accession.remaining?.grams)
                .set(REMAINING_QUANTITY, accession.remaining?.quantity)
                .set(REMAINING_UNITS_ID, accession.remaining?.units)
                .set(SOURCE_PLANT_ORIGIN_ID, accession.sourcePlantOrigin)
                .set(SPECIES_ENDANGERED_TYPE_ID, accession.endangered)
                .set(SPECIES_FAMILY_ID, speciesStore.getSpeciesFamilyId(accession.family))
                .set(SPECIES_ID, speciesStore.getSpeciesId(accession.species))
                .set(SPECIES_RARE_TYPE_ID, accession.rare)
                .set(STATE_ID, accession.state)
                .set(STORAGE_LOCATION_ID, getStorageLocationId(accession.storageLocation))
                .set(STORAGE_NOTES, accession.storageNotes)
                .set(STORAGE_PACKETS, accession.storagePackets)
                .set(STORAGE_STAFF_RESPONSIBLE, accession.storageStaffResponsible)
                .set(STORAGE_START_DATE, accession.storageStartDate)
                .set(SUBSET_COUNT, accession.subsetCount)
                .set(SUBSET_WEIGHT_GRAMS, accession.subsetWeightQuantity?.grams)
                .set(SUBSET_WEIGHT_QUANTITY, accession.subsetWeightQuantity?.quantity)
                .set(SUBSET_WEIGHT_UNITS_ID, accession.subsetWeightQuantity?.units)
                .set(TARGET_STORAGE_CONDITION, accession.targetStorageCondition)
                .set(TOTAL_GRAMS, accession.total?.grams)
                .set(TOTAL_QUANTITY, accession.total?.quantity)
                .set(TOTAL_UNITS_ID, accession.total?.units)
                .set(TOTAL_VIABILITY_PERCENT, accession.totalViabilityPercent)
                .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
                .where(NUMBER.eq(accessionNumber))
                .and(FACILITY_ID.eq(config.facilityId))
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
  fun updateAndFetch(accession: AccessionModel, accessionNumber: String): AccessionModel {
    val updated =
        if (update(accessionNumber, accession)) {
          fetchByNumber(accessionNumber)
        } else {
          null
        }

    return updated ?: throw AccessionNotFoundException(accessionNumber)
  }

  /**
   * Returns the accession data that would result from updating an accession, but does not write the
   * modified data to the database.
   *
   * @throws AccessionNotFoundException if the accession doesn't exist.
   */
  fun dryRun(accession: AccessionModel, accessionNumber: String): AccessionModel {
    val existing =
        fetchByNumber(accessionNumber) ?: throw AccessionNotFoundException(accessionNumber)
    return accession.withCalculatedValues(clock, existing)
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
                .update(ACCESSIONS)
                .set(ACCESSIONS.SPECIES_ID, existingSpeciesId)
                .where(ACCESSIONS.SPECIES_ID.eq(speciesId))
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

    return with(ACCESSIONS) {
      dslContext
          .select(NUMBER)
          .from(ACCESSIONS)
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

  private fun fetchSecondaryCollectorNames(accessionId: Long): Set<String> {
    return dslContext
        .select(COLLECTORS.NAME)
        .from(COLLECTORS)
        .join(ACCESSION_SECONDARY_COLLECTORS)
        .on(COLLECTORS.ID.eq(ACCESSION_SECONDARY_COLLECTORS.COLLECTOR_ID))
        .where(ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID.eq(accessionId))
        .orderBy(COLLECTORS.NAME)
        .fetch(COLLECTORS.NAME)
        .filterNotNull()
        .toSet()
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
                ACCESSION_SECONDARY_COLLECTORS,
                ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID,
                ACCESSION_SECONDARY_COLLECTORS.COLLECTOR_ID)
            .values(accessionId, collectorId)
            .execute()
      }
    }
  }

  private fun getCollectorId(name: String?): Long? {
    return support.getOrInsertId(name, COLLECTORS.ID, COLLECTORS.NAME, COLLECTORS.FACILITY_ID)
  }

  private fun getStorageLocationId(name: String?): Long? {
    return support.getId(
        name, STORAGE_LOCATIONS.ID, STORAGE_LOCATIONS.NAME, STORAGE_LOCATIONS.FACILITY_ID)
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
            .from(ACCESSIONS)
            .where(ACCESSIONS.CREATED_TIME.le(asOf.toInstant()))
            .and(
                ACCESSIONS
                    .STATE_ID
                    .`in`(statesByActive[AccessionActive.Active])
                    .orNotExists(
                        dslContext
                            .selectOne()
                            .from(ACCESSION_STATE_HISTORY)
                            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(ACCESSIONS.ID))
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
                    .selectDistinct(ACCESSIONS.ID)
                    .from(ACCESSION_STATE_HISTORY)
                    .join(ACCESSIONS)
                    .on(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(ACCESSIONS.ID))
                    .where(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(state))
                    .and(ACCESSIONS.STATE_ID.eq(state))
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
    val query = dslContext.select(DSL.count()).from(ACCESSIONS).where(ACCESSIONS.STATE_ID.eq(state))

    log.debug("Accession state count query: ${query.getSQL(ParamType.INLINED)}")

    return log.debugWithTiming("Accession state count query") { query.fetchOne()?.value1() ?: 0 }
  }

  fun fetchDryingMoveDue(after: TemporalAccessor, until: TemporalAccessor): Map<String, Long> {
    return with(ACCESSIONS) {
      dslContext
          .select(ID, NUMBER)
          .from(ACCESSIONS)
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
  ): Map<String, GerminationTestsRow> {
    return dslContext
        .select(ACCESSIONS.NUMBER, GERMINATION_TESTS.asterisk())
        .from(ACCESSIONS)
        .join(GERMINATION_TESTS)
        .on(GERMINATION_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID))
        .where(ACCESSIONS.STATE_ID.`in`(AccessionState.Processing, AccessionState.Processed))
        .and(GERMINATION_TESTS.START_DATE.le(LocalDate.ofInstant(until.toInstant(), clock.zone)))
        .and(GERMINATION_TESTS.START_DATE.gt(LocalDate.ofInstant(after.toInstant(), clock.zone)))
        .fetch { it[ACCESSIONS.NUMBER]!! to it.into(GerminationTestsRow::class.java)!! }
        .toMap()
  }

  fun fetchWithdrawalDue(after: TemporalAccessor, until: TemporalAccessor): Map<String, Long> {
    return dslContext
        .selectDistinct(ACCESSIONS.ID, ACCESSIONS.NUMBER)
        .from(ACCESSIONS)
        .join(WITHDRAWALS)
        .on(WITHDRAWALS.ACCESSION_ID.eq(ACCESSIONS.ID))
        .where(WITHDRAWALS.DATE.le(LocalDate.ofInstant(until.toInstant(), clock.zone)))
        .and(WITHDRAWALS.DATE.gt(LocalDate.ofInstant(after.toInstant(), clock.zone)))
        .fetch { it[ACCESSIONS.NUMBER]!! to it[ACCESSIONS.ID]!! }
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
   * Note that there is a bit of a race condition if multiple terraware-server instances happen to
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
}
