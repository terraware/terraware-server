package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.AppDeviceStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.sequences.ACCESSION_NUMBER_SEQ
import com.terraformation.backend.db.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.db.tables.references.ACCESSION_SECONDARY_COLLECTORS
import com.terraformation.backend.db.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.db.tables.references.PHOTOS
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.model.AccessionActive
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.AccessionSource
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.toActiveEnum
import com.terraformation.backend.species.SpeciesService
import com.terraformation.backend.time.toInstant
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.conf.ParamType
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class AccessionStore(
    private val dslContext: DSLContext,
    private val appDeviceStore: AppDeviceStore,
    private val bagStore: BagStore,
    private val geolocationStore: GeolocationStore,
    private val viabilityTestStore: ViabilityTestStore,
    private val parentStore: ParentStore,
    private val speciesService: SpeciesService,
    private val withdrawalStore: WithdrawalStore,
    private val clock: Clock,
) {
  companion object {
    /** Number of times to try generating a unique accession number before giving up. */
    private const val ACCESSION_NUMBER_RETRIES = 10
  }

  private val log = perClassLogger()

  fun fetchOneById(accessionId: AccessionId): AccessionModel {
    requirePermissions { readAccession(accessionId) }

    // The accession data forms a tree structure. The parent node is the data from the accessions
    // table itself, as well as data in reference tables where a given accession can only have a
    // single value. For example, there is a species table, but an accession only has one species,
    // so we can consider species to be an attribute of the accession rather than a child entity.
    //
    // Under the parent, there are things an accession can have zero or more of, e.g., withdrawals
    // or photos. We query those things using multisets, which causes the list of values to appear
    // as a single field in the top-level query. Each multiset field gets translated into a subquery
    // by jOOQ, so we can get everything in one database request.
    val appDeviceField = appDeviceStore.appDeviceMultiset()
    val bagNumbersField = bagStore.bagNumbersMultiset()
    val geolocationsField = geolocationStore.geolocationsMultiset()
    val photoFilenamesField = photoFilenamesMultiset()
    val secondaryCollectorsField = secondaryCollectorsMultiset()
    val viabilityTestsField = viabilityTestStore.viabilityTestsMultiset()
    val viabilityTestTypesField = viabilityTestStore.viabilityTestTypesMultiset()
    val withdrawalsField = withdrawalStore.withdrawalsMultiset()

    val record =
        dslContext
            .select(
                ACCESSIONS.asterisk(),
                ACCESSIONS.species().COMMON_NAME,
                ACCESSIONS.species().SCIENTIFIC_NAME,
                ACCESSIONS.STATE_ID,
                ACCESSIONS.storageLocations().NAME,
                ACCESSIONS.storageLocations().CONDITION_ID,
                ACCESSIONS.TARGET_STORAGE_CONDITION,
                ACCESSIONS.PROCESSING_METHOD_ID,
                ACCESSIONS.PROCESSING_STAFF_RESPONSIBLE,
                appDeviceField,
                bagNumbersField,
                geolocationsField,
                photoFilenamesField,
                secondaryCollectorsField,
                viabilityTestsField,
                viabilityTestTypesField,
                withdrawalsField,
            )
            .from(ACCESSIONS)
            .where(ACCESSIONS.ID.eq(accessionId))
            .fetchOne()
            ?: throw AccessionNotFoundException(accessionId)

    val source =
        if (record[appDeviceField] != null) AccessionSource.SeedCollectorApp
        else AccessionSource.Web

    return with(ACCESSIONS) {
      AccessionModel(
          accessionNumber = record[NUMBER],
          bagNumbers = record[bagNumbersField],
          checkedInTime = record[CHECKED_IN_TIME],
          collectedDate = record[COLLECTED_DATE],
          cutTestSeedsCompromised = record[CUT_TEST_SEEDS_COMPROMISED],
          cutTestSeedsEmpty = record[CUT_TEST_SEEDS_EMPTY],
          cutTestSeedsFilled = record[CUT_TEST_SEEDS_FILLED],
          deviceInfo = record[appDeviceField],
          dryingEndDate = record[DRYING_END_DATE],
          dryingMoveDate = record[DRYING_MOVE_DATE],
          dryingStartDate = record[DRYING_START_DATE],
          endangered = record[SPECIES_ENDANGERED_TYPE_ID],
          environmentalNotes = record[ENVIRONMENTAL_NOTES],
          estimatedSeedCount = record[EST_SEED_COUNT],
          facilityId = record[FACILITY_ID],
          family = record[FAMILY_NAME],
          fieldNotes = record[FIELD_NOTES],
          founderId = record[FOUNDER_ID],
          geolocations = record[geolocationsField],
          id = accessionId,
          landowner = record[COLLECTION_SITE_LANDOWNER],
          latestViabilityPercent = record[LATEST_VIABILITY_PERCENT],
          latestViabilityTestDate = record[LATEST_GERMINATION_RECORDING_DATE],
          numberOfTrees = record[TREES_COLLECTED_FROM],
          nurseryStartDate = record[NURSERY_START_DATE],
          photoFilenames = record[photoFilenamesField],
          primaryCollector = record[PRIMARY_COLLECTOR_NAME],
          processingMethod = record[PROCESSING_METHOD_ID],
          processingNotes = record[PROCESSING_NOTES],
          processingStaffResponsible = record[PROCESSING_STAFF_RESPONSIBLE],
          processingStartDate = record[PROCESSING_START_DATE],
          rare = record[RARE_TYPE_ID],
          receivedDate = record[RECEIVED_DATE],
          remaining = SeedQuantityModel.of(record[REMAINING_QUANTITY], record[REMAINING_UNITS_ID]),
          secondaryCollectors = record[secondaryCollectorsField],
          siteLocation = record[COLLECTION_SITE_NAME],
          source = source,
          sourcePlantOrigin = record[SOURCE_PLANT_ORIGIN_ID],
          species = record[species().SCIENTIFIC_NAME],
          speciesCommonName = record[species().COMMON_NAME],
          speciesId = record[SPECIES_ID],
          state = record[STATE_ID]!!,
          storageCondition = record[storageLocations().CONDITION_ID],
          storageLocation = record[storageLocations().NAME],
          storageNotes = record[STORAGE_NOTES],
          storagePackets = record[STORAGE_PACKETS],
          storageStaffResponsible = record[STORAGE_STAFF_RESPONSIBLE],
          storageStartDate = record[STORAGE_START_DATE],
          subsetCount = record[SUBSET_COUNT],
          subsetWeightQuantity =
              SeedQuantityModel.of(
                  record[SUBSET_WEIGHT_QUANTITY],
                  record[SUBSET_WEIGHT_UNITS_ID],
              ),
          targetStorageCondition = record[TARGET_STORAGE_CONDITION],
          total = SeedQuantityModel.of(record[TOTAL_QUANTITY], record[TOTAL_UNITS_ID]),
          totalViabilityPercent = record[TOTAL_VIABILITY_PERCENT],
          viabilityTests = record[viabilityTestsField],
          viabilityTestTypes = record[viabilityTestTypesField],
          withdrawals = record[withdrawalsField],
      )
    }
  }

  fun create(accession: AccessionModel): AccessionModel {
    val facilityId =
        accession.facilityId ?: throw IllegalArgumentException("No facility ID specified")
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    requirePermissions { createAccession(facilityId) }

    var attemptsRemaining = ACCESSION_NUMBER_RETRIES

    while (attemptsRemaining-- > 0) {
      val accessionNumber = generateAccessionNumber()

      try {
        val accessionId =
            dslContext.transactionResult { _ ->
              val appDeviceId =
                  accession.deviceInfo?.nullIfEmpty()?.let { appDeviceStore.getOrInsertDevice(it) }
              val speciesId =
                  accession.species?.let { speciesService.getOrCreateSpecies(organizationId, it) }
              val state = AccessionState.AwaitingCheckIn

              val accessionId =
                  with(ACCESSIONS) {
                    dslContext
                        .insertInto(ACCESSIONS)
                        .set(APP_DEVICE_ID, appDeviceId)
                        .set(COLLECTED_DATE, accession.collectedDate)
                        .set(COLLECTION_SITE_LANDOWNER, accession.landowner)
                        .set(COLLECTION_SITE_NAME, accession.siteLocation)
                        .set(CREATED_BY, currentUser().userId)
                        .set(CREATED_TIME, clock.instant())
                        .set(CUT_TEST_SEEDS_COMPROMISED, accession.cutTestSeedsCompromised)
                        .set(CUT_TEST_SEEDS_EMPTY, accession.cutTestSeedsEmpty)
                        .set(CUT_TEST_SEEDS_FILLED, accession.cutTestSeedsFilled)
                        .set(ENVIRONMENTAL_NOTES, accession.environmentalNotes)
                        .set(FACILITY_ID, facilityId)
                        .set(FAMILY_NAME, accession.family)
                        .set(FIELD_NOTES, accession.fieldNotes)
                        .set(FOUNDER_ID, accession.founderId)
                        .set(
                            LATEST_GERMINATION_RECORDING_DATE,
                            accession.calculateLatestViabilityRecordingDate())
                        .set(LATEST_VIABILITY_PERCENT, accession.calculateLatestViabilityPercent())
                        .set(MODIFIED_BY, currentUser().userId)
                        .set(MODIFIED_TIME, clock.instant())
                        .set(NUMBER, accessionNumber)
                        .set(NURSERY_START_DATE, accession.nurseryStartDate)
                        .set(PRIMARY_COLLECTOR_NAME, accession.primaryCollector)
                        .set(RARE_TYPE_ID, accession.rare)
                        .set(RECEIVED_DATE, accession.receivedDate)
                        .set(SOURCE_PLANT_ORIGIN_ID, accession.sourcePlantOrigin)
                        .set(SPECIES_ENDANGERED_TYPE_ID, accession.endangered)
                        .set(SPECIES_ID, speciesId)
                        .set(STATE_ID, state)
                        .set(
                            STORAGE_LOCATION_ID,
                            getStorageLocationId(facilityId, accession.storageLocation))
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
                    .set(NEW_STATE_ID, state)
                    .set(UPDATED_TIME, clock.instant())
                    .execute()
              }

              insertSecondaryCollectors(accessionId, accession.secondaryCollectors)
              bagStore.updateBags(accessionId, emptySet(), accession.bagNumbers)
              geolocationStore.updateGeolocations(accessionId, emptySet(), accession.geolocations)
              viabilityTestStore.updateViabilityTestTypes(
                  accessionId, emptySet(), accession.viabilityTestTypes)
              viabilityTestStore.updateViabilityTests(
                  accessionId, emptyList(), accession.viabilityTests)
              withdrawalStore.updateWithdrawals(accessionId, emptyList(), accession.withdrawals)

              accessionId
            }

        return fetchOneById(accessionId)
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

  fun update(updated: AccessionModel) {
    val accessionId = updated.id ?: throw IllegalArgumentException("No accession ID specified")
    val existing = fetchOneById(accessionId)
    val existingFacilityId =
        existing.facilityId ?: throw IllegalStateException("Accession has no facility ID")
    val facilityId = updated.facilityId ?: existing.facilityId
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    if (facilityId != existingFacilityId &&
        organizationId != parentStore.getOrganizationId(existingFacilityId)) {
      throw FacilityNotFoundException(facilityId)
    }

    requirePermissions { updateAccession(accessionId) }

    // Some fields are significant to the state machine, but can't be directly set on update; pull
    // them from the existing accession for purposes of value calculation.
    val updatedWithReadOnlyValues = updated.copy(checkedInTime = existing.checkedInTime)

    val accession = updatedWithReadOnlyValues.withCalculatedValues(clock, existing)
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

      val existingTests: MutableList<ViabilityTestModel> = existing.viabilityTests.toMutableList()
      val withdrawals =
          accession.withdrawals.map { withdrawal ->
            withdrawal.viabilityTest?.let { viabilityTest ->
              if (viabilityTest.id == null) {
                val insertedTest =
                    viabilityTestStore.insertViabilityTest(accessionId, viabilityTest)
                existingTests.add(insertedTest)
                withdrawal.copy(viabilityTest = insertedTest, viabilityTestId = insertedTest.id)
              } else {
                withdrawal
              }
            }
                ?: withdrawal
          }

      val viabilityTests = withdrawals.mapNotNull { it.viabilityTest }

      bagStore.updateBags(accessionId, existing.bagNumbers, accession.bagNumbers)
      geolocationStore.updateGeolocations(
          accessionId, existing.geolocations, accession.geolocations)
      viabilityTestStore.updateViabilityTestTypes(
          accessionId, existing.viabilityTestTypes, accession.viabilityTestTypes)
      viabilityTestStore.updateViabilityTests(accessionId, existingTests, viabilityTests)
      withdrawalStore.updateWithdrawals(accessionId, existing.withdrawals, withdrawals)

      insertStateHistory(existing, accession)

      val speciesId =
          accession.species?.let { speciesService.getOrCreateSpecies(organizationId, it) }

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
                .set(FACILITY_ID, facilityId)
                .set(FAMILY_NAME, accession.family)
                .set(FIELD_NOTES, accession.fieldNotes)
                .set(FOUNDER_ID, accession.founderId)
                .set(LATEST_GERMINATION_RECORDING_DATE, accession.latestViabilityTestDate)
                .set(LATEST_VIABILITY_PERCENT, accession.latestViabilityPercent)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .set(NURSERY_START_DATE, accession.nurseryStartDate)
                .set(PRIMARY_COLLECTOR_NAME, accession.primaryCollector)
                .set(PROCESSING_METHOD_ID, accession.processingMethod)
                .set(PROCESSING_NOTES, accession.processingNotes)
                .set(PROCESSING_STAFF_RESPONSIBLE, accession.processingStaffResponsible)
                .set(PROCESSING_START_DATE, accession.processingStartDate)
                .set(RARE_TYPE_ID, accession.rare)
                .set(RECEIVED_DATE, accession.receivedDate)
                .set(REMAINING_GRAMS, accession.remaining?.grams)
                .set(REMAINING_QUANTITY, accession.remaining?.quantity)
                .set(REMAINING_UNITS_ID, accession.remaining?.units)
                .set(SOURCE_PLANT_ORIGIN_ID, accession.sourcePlantOrigin)
                .set(SPECIES_ENDANGERED_TYPE_ID, accession.endangered)
                .set(SPECIES_ID, speciesId)
                .set(STATE_ID, accession.state)
                .set(
                    STORAGE_LOCATION_ID,
                    getStorageLocationId(facilityId, accession.storageLocation))
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
                .where(ID.eq(accessionId))
                .execute()
          }

      if (rowsUpdated != 1) {
        log.error("Accession $accessionId exists in database but update failed")
        throw DataAccessException("Unable to update accession $accessionId")
      }
    }
  }

  /**
   * Deletes all the non-photo data for an accession. Photos must already be deleted or this will
   * throw an exception. You probably want to call [AccessionService.deleteAccession] instead of
   * this.
   */
  fun delete(accessionId: AccessionId) {
    requirePermissions { deleteAccession(accessionId) }

    val model = fetchOneById(accessionId)

    dslContext.transaction { _ ->
      bagStore.updateBags(accessionId, model.bagNumbers, emptySet())
      geolocationStore.updateGeolocations(accessionId, model.geolocations, emptySet())
      viabilityTestStore.updateViabilityTestTypes(accessionId, model.viabilityTestTypes, emptySet())
      viabilityTestStore.updateViabilityTests(accessionId, model.viabilityTests, emptyList())
      withdrawalStore.updateWithdrawals(accessionId, model.withdrawals, emptyList())

      dslContext
          .deleteFrom(ACCESSION_SECONDARY_COLLECTORS)
          .where(ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID.eq(accessionId))
          .execute()
      dslContext
          .deleteFrom(ACCESSION_STATE_HISTORY)
          .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(accessionId))
          .execute()
      dslContext.deleteFrom(ACCESSIONS).where(ACCESSIONS.ID.eq(accessionId)).execute()
    }
  }

  /**
   * Records a history entry for a state transition if an accession's state has changed as the
   * result of a modification.
   */
  private fun insertStateHistory(before: AccessionModel, after: AccessionModel) {
    val accessionId = before.id ?: throw IllegalArgumentException("Existing accession has no ID")

    if (before.state != after.state) {
      before.getStateTransition(after, clock)?.let { stateTransition ->
        log.info(
            "Accession $accessionId transitioning from ${before.state} to " +
                "${stateTransition.newState}: ${stateTransition.reason}",
        )

        with(ACCESSION_STATE_HISTORY) {
          dslContext
              .insertInto(ACCESSION_STATE_HISTORY)
              .set(ACCESSION_ID, accessionId)
              .set(NEW_STATE_ID, stateTransition.newState)
              .set(OLD_STATE_ID, before.state)
              .set(REASON, stateTransition.reason)
              .set(UPDATED_TIME, clock.instant())
              .execute()
        }
      }
    }
  }

  /**
   * Updates an accession and returns the modified accession data including any computed field
   * values.
   */
  fun updateAndFetch(accession: AccessionModel): AccessionModel {
    val accessionId = accession.id ?: throw IllegalArgumentException("Missing accession ID")
    update(accession)
    return fetchOneById(accessionId)
  }

  /**
   * Marks an accession as checked in and returns the modified accession data including any computed
   * field values.
   *
   * @throws AccessionNotFoundException The accession did not exist or wasn't accessible by the
   * current user.
   */
  fun checkIn(accessionId: AccessionId): AccessionModel {
    val accession = fetchOneById(accessionId)

    requirePermissions { updateAccession(accessionId) }

    if (accession.checkedInTime != null) {
      log.info("Accession $accessionId is already checked in; ignoring request to check in again")
      return accession
    }

    // Don't record the time with sub-second precision; it is not useful and makes exact searches
    // problematic in the face of systems with different levels of precision in their native time
    // representations.
    val checkedInTime = clock.instant().truncatedTo(ChronoUnit.SECONDS)
    val withCheckedInTime =
        accession.copy(checkedInTime = checkedInTime).withCalculatedValues(clock, accession)

    dslContext.transaction { _ ->
      with(ACCESSIONS) {
        dslContext
            .update(ACCESSIONS)
            .set(CHECKED_IN_TIME, checkedInTime)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .set(STATE_ID, withCheckedInTime.state)
            .where(ID.eq(accessionId))
            .and(CHECKED_IN_TIME.isNull)
            .execute()
      }

      insertStateHistory(accession, withCheckedInTime)
    }

    return withCheckedInTime
  }

  /**
   * Returns the accession data that would result from updating an accession, but does not write the
   * modified data to the database.
   *
   * @throws AccessionNotFoundException if the accession doesn't exist.
   */
  fun dryRun(accession: AccessionModel): AccessionModel {
    val accessionId = accession.id ?: throw IllegalArgumentException("Missing accession ID")
    val existing = fetchOneById(accessionId)
    return accession.withCalculatedValues(clock, existing)
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
          .select(ID)
          .from(ACCESSIONS)
          .where(
              STATE_ID.eq(AccessionState.Processing)
                  .and(PROCESSING_START_DATE.le(twoWeeksAgo).or(DRYING_START_DATE.le(today)))
                  .or(STATE_ID.eq(AccessionState.Processed).and(DRYING_START_DATE.le(today)))
                  .or(
                      STATE_ID.eq(AccessionState.Drying)
                          .and(STORAGE_START_DATE.le(today).or(DRYING_END_DATE.le(today))))
                  .or(STATE_ID.eq(AccessionState.Dried).and(STORAGE_START_DATE.le(today))))
          .fetch(ID)
          .mapNotNull { accessionId ->
            // This is an N+1 query which isn't ideal but we are going to be processing these one
            // at a time anyway so optimizing this to a single SELECT wouldn't help much.
            fetchOneById(accessionId!!)
          }
    }
  }

  private fun photoFilenamesMultiset(): Field<List<String>> {
    return DSL.multiset(
            DSL.select(PHOTOS.FILE_NAME)
                .from(ACCESSION_PHOTOS)
                .join(PHOTOS)
                .on(ACCESSION_PHOTOS.PHOTO_ID.eq(PHOTOS.ID))
                .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(ACCESSIONS.ID))
                .orderBy(PHOTOS.CREATED_TIME))
        .convertFrom { result -> result.map { it.value1() } }
  }

  private fun secondaryCollectorsMultiset(): Field<Set<String>> {
    return DSL.multiset(
            DSL.select(ACCESSION_SECONDARY_COLLECTORS.NAME)
                .from(ACCESSION_SECONDARY_COLLECTORS)
                .where(ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID.eq(ACCESSIONS.ID))
                .orderBy(ACCESSION_SECONDARY_COLLECTORS.NAME))
        .convertFrom { result -> result.map { it.value1() }.toSet() }
  }

  private fun insertSecondaryCollectors(
      accessionId: AccessionId,
      secondaryCollectors: Collection<String>?
  ) {
    secondaryCollectors?.forEach { name ->
      dslContext
          .insertInto(ACCESSION_SECONDARY_COLLECTORS)
          .set(ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID, accessionId)
          .set(ACCESSION_SECONDARY_COLLECTORS.NAME, name)
          .execute()
    }
  }

  private fun getStorageLocationId(facilityId: FacilityId, name: String?): StorageLocationId? {
    return if (name == null) {
      null
    } else {
      dslContext
          .select(STORAGE_LOCATIONS.ID)
          .from(STORAGE_LOCATIONS.ID.table)
          .where(STORAGE_LOCATIONS.NAME.eq(name))
          .and(STORAGE_LOCATIONS.FACILITY_ID.eq(facilityId))
          .fetchOne(STORAGE_LOCATIONS.ID)
          ?: throw IllegalArgumentException("Unable to find storage location $name")
    }
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
  fun countActive(facilityId: FacilityId, asOf: TemporalAccessor): Int {
    requirePermissions { readFacility(facilityId) }
    val condition = ACCESSIONS.FACILITY_ID.eq(facilityId)
    return countActive(condition, asOf)
  }

  fun countActive(organizationId: OrganizationId, asOf: TemporalAccessor): Int {
    requirePermissions { readOrganization(organizationId) }
    val condition = ACCESSIONS.facilities().ORGANIZATION_ID.eq(organizationId)
    return countActive(condition, asOf)
  }

  /**
   * Returns the number of accessions that entered a state during a time period and are still in
   * that state now.
   *
   * @param sinceAfter Only count accessions that changed to the state at or after this time.
   * @param sinceBefore Only count accessions that changed to the state at or before this time.
   */
  fun countInState(
      facilityId: FacilityId,
      state: AccessionState,
      sinceAfter: TemporalAccessor? = null,
      sinceBefore: TemporalAccessor? = null,
  ): Int {
    requirePermissions { readFacility(facilityId) }

    val condition = ACCESSIONS.FACILITY_ID.eq(facilityId)
    return countInState(condition, state, sinceAfter, sinceBefore)
  }

  /** Returns the number of accessions currently in a given state. */
  fun countInState(facilityId: FacilityId, state: AccessionState): Int {
    requirePermissions { readFacility(facilityId) }

    val condition = ACCESSIONS.FACILITY_ID.eq(facilityId)
    return countInState(condition, state)
  }

  fun countInState(
      organizationId: OrganizationId,
      state: AccessionState,
      sinceAfter: TemporalAccessor? = null,
      sinceBefore: TemporalAccessor? = null,
  ): Int {
    requirePermissions { readOrganization(organizationId) }

    val condition = ACCESSIONS.facilities().ORGANIZATION_ID.eq(organizationId)
    return countInState(condition, state, sinceAfter, sinceBefore)
  }

  fun countInState(organizationId: OrganizationId, state: AccessionState): Int {
    requirePermissions { readOrganization(organizationId) }

    val condition = ACCESSIONS.facilities().ORGANIZATION_ID.eq(organizationId)
    return countInState(condition, state)
  }

  fun countFamilies(facilityId: FacilityId, asOf: TemporalAccessor): Int {
    requirePermissions { readFacility(facilityId) }

    val condition = ACCESSIONS.FACILITY_ID.eq(facilityId)
    return countFamilies(condition, asOf)
  }

  fun countFamilies(organizationId: OrganizationId, asOf: TemporalAccessor): Int {
    requirePermissions { readOrganization(organizationId) }

    val condition = ACCESSIONS.facilities().ORGANIZATION_ID.eq(organizationId)
    return countFamilies(condition, asOf)
  }

  fun fetchDryingMoveDue(
      after: TemporalAccessor,
      until: TemporalAccessor
  ): Map<String, AccessionId> {
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

  fun fetchDryingEndDue(
      after: TemporalAccessor,
      until: TemporalAccessor
  ): Map<String, AccessionId> {
    return with(ACCESSIONS) {
      dslContext
          .select(ID, NUMBER)
          .from(ACCESSIONS)
          .where(STATE_ID.`in`(AccessionState.Drying, AccessionState.Dried))
          .and(DRYING_END_DATE.le(LocalDate.ofInstant(until.toInstant(), clock.zone)))
          .and(DRYING_END_DATE.gt(LocalDate.ofInstant(after.toInstant(), clock.zone)))
          .fetch { it[NUMBER]!! to it[ID]!! }
          .toMap()
    }
  }

  fun fetchViabilityTestDue(
      after: TemporalAccessor,
      until: TemporalAccessor
  ): Map<String, ViabilityTestsRow> {
    return dslContext
        .select(ACCESSIONS.NUMBER, VIABILITY_TESTS.asterisk())
        .from(ACCESSIONS)
        .join(VIABILITY_TESTS)
        .on(VIABILITY_TESTS.ACCESSION_ID.eq(ACCESSIONS.ID))
        .where(ACCESSIONS.STATE_ID.`in`(AccessionState.Processing, AccessionState.Processed))
        .and(VIABILITY_TESTS.START_DATE.le(LocalDate.ofInstant(until.toInstant(), clock.zone)))
        .and(VIABILITY_TESTS.START_DATE.gt(LocalDate.ofInstant(after.toInstant(), clock.zone)))
        .fetch { it[ACCESSIONS.NUMBER]!! to it.into(ViabilityTestsRow::class.java)!! }
        .toMap()
  }

  fun fetchWithdrawalDue(
      after: TemporalAccessor,
      until: TemporalAccessor
  ): Map<String, AccessionId> {
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

  private fun countActive(condition: Condition, asOf: TemporalAccessor): Int {
    val statesByActive = AccessionState.values().groupBy { it.toActiveEnum() }

    val query =
        dslContext
            .select(DSL.count())
            .from(ACCESSIONS)
            .where(condition)
            .and(ACCESSIONS.CREATED_TIME.le(asOf.toInstant()))
            .and(
                ACCESSIONS.STATE_ID.`in`(statesByActive[AccessionActive.Active])
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

  private fun countInState(
      condition: Condition,
      state: AccessionState,
      sinceAfter: TemporalAccessor? = null,
      sinceBefore: TemporalAccessor? = null,
  ): Int {
    val query =
        dslContext
            .select(DSL.count())
            .from(
                DSL.selectDistinct(ACCESSIONS.ID)
                    .from(ACCESSION_STATE_HISTORY)
                    .join(ACCESSIONS)
                    .on(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(ACCESSIONS.ID))
                    .where(condition)
                    .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(state))
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

    val sql = query.getSQL(ParamType.INLINED)

    return log.debugWithTiming("Accession state count with time bounds: $sql") {
      query.fetchOne()?.value1() ?: 0
    }
  }

  private fun countInState(condition: Condition, state: AccessionState): Int {
    val query =
        dslContext
            .select(DSL.count())
            .from(ACCESSIONS)
            .where(condition)
            .and(ACCESSIONS.STATE_ID.eq(state))

    val sql = query.getSQL(ParamType.INLINED)

    return log.debugWithTiming("Accession state count query: $sql") {
      query.fetchOne()?.value1() ?: 0
    }
  }

  private fun countFamilies(condition: Condition, asOf: TemporalAccessor): Int {
    return dslContext
        .select(DSL.countDistinct(ACCESSIONS.FAMILY_NAME))
        .from(ACCESSIONS)
        .where(condition)
        .and(ACCESSIONS.CREATED_TIME.le(asOf.toInstant()))
        .fetchOne()
        ?.value1()
        ?: 0
  }
}
