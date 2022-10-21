package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.IdentifierType
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.PHOTOS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_COLLECTORS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_QUANTITY_HISTORY
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.AccessionSummaryStatistics
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.activeValues
import com.terraformation.backend.seedbank.model.isV2Compatible
import com.terraformation.backend.time.toInstant
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record1
import org.jooq.Select
import org.jooq.conf.ParamType
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class AccessionStore(
    private val dslContext: DSLContext,
    private val bagStore: BagStore,
    private val geolocationStore: GeolocationStore,
    private val viabilityTestStore: ViabilityTestStore,
    private val parentStore: ParentStore,
    private val withdrawalStore: WithdrawalStore,
    private val clock: Clock,
    private val messages: Messages,
    private val identifierGenerator: IdentifierGenerator,
) {
  companion object {
    /** Number of times to try generating a unique accession number before giving up. */
    private const val ACCESSION_NUMBER_RETRIES = 10
  }

  private val log = perClassLogger()

  fun fetchOneById(accessionId: AccessionId): AccessionModel {
    requirePermissions { readAccession(accessionId) }

    return fetchOneByCondition(ACCESSIONS.ID.eq(accessionId))
        ?: throw AccessionNotFoundException(accessionId)
  }

  fun fetchOneByNumber(facilityId: FacilityId, accessionNumber: String): AccessionModel? {
    val model =
        fetchOneByCondition(
            ACCESSIONS.FACILITY_ID.eq(facilityId).and(ACCESSIONS.NUMBER.eq(accessionNumber)))

    return if (model?.id != null && currentUser().canReadAccession(model.id)) {
      model
    } else {
      null
    }
  }

  private fun fetchOneByCondition(condition: Condition): AccessionModel? {
    // The accession data forms a tree structure. The parent node is the data from the accessions
    // table itself, as well as data in reference tables where a given accession can only have a
    // single value. For example, there is a species table, but an accession only has one species,
    // so we can consider species to be an attribute of the accession rather than a child entity.
    //
    // Under the parent, there are things an accession can have zero or more of, e.g., withdrawals
    // or photos. We query those things using multisets, which causes the list of values to appear
    // as a single field in the top-level query. Each multiset field gets translated into a subquery
    // by jOOQ, so we can get everything in one database request.
    val bagNumbersField = bagStore.bagNumbersMultiset()
    val geolocationsField = geolocationStore.geolocationsMultiset()
    val photoFilenamesField = photoFilenamesMultiset()
    val collectorsField = collectorsMultiset()
    val viabilityTestsField = viabilityTestStore.viabilityTestsMultiset()
    val withdrawalsField = withdrawalStore.withdrawalsMultiset()

    val record =
        dslContext
            .select(
                ACCESSIONS.asterisk(),
                ACCESSIONS.species.COMMON_NAME,
                ACCESSIONS.species.SCIENTIFIC_NAME,
                ACCESSIONS.STATE_ID,
                ACCESSIONS.storageLocations.NAME,
                ACCESSIONS.storageLocations.CONDITION_ID,
                ACCESSIONS.TARGET_STORAGE_CONDITION,
                ACCESSIONS.PROCESSING_METHOD_ID,
                ACCESSIONS.PROCESSING_STAFF_RESPONSIBLE,
                bagNumbersField,
                geolocationsField,
                photoFilenamesField,
                collectorsField,
                viabilityTestsField,
                withdrawalsField,
            )
            .from(ACCESSIONS)
            .where(condition)
            .fetchOne()
            ?: return null

    return with(ACCESSIONS) {
      AccessionModel(
          accessionNumber = record[NUMBER],
          bagNumbers = record[bagNumbersField],
          checkedInTime = record[CHECKED_IN_TIME],
          collectedDate = record[COLLECTED_DATE],
          collectionSiteCity = record[COLLECTION_SITE_CITY],
          collectionSiteCountryCode = record[COLLECTION_SITE_COUNTRY_CODE],
          collectionSiteCountrySubdivision = record[COLLECTION_SITE_COUNTRY_SUBDIVISION],
          collectionSiteLandowner = record[COLLECTION_SITE_LANDOWNER],
          collectionSiteName = record[COLLECTION_SITE_NAME],
          collectionSiteNotes = record[COLLECTION_SITE_NOTES],
          collectionSource = record[COLLECTION_SOURCE_ID],
          collectors = record[collectorsField],
          createdTime = record[CREATED_TIME],
          dryingEndDate = record[DRYING_END_DATE],
          dryingMoveDate = record[DRYING_MOVE_DATE],
          dryingStartDate = record[DRYING_START_DATE],
          estimatedSeedCount = record[EST_SEED_COUNT],
          estimatedWeight =
              SeedQuantityModel.of(record[EST_WEIGHT_QUANTITY], record[EST_WEIGHT_UNITS_ID]),
          facilityId = record[FACILITY_ID],
          fieldNotes = record[FIELD_NOTES],
          founderId = record[FOUNDER_ID],
          geolocations = record[geolocationsField],
          id = record[ID],
          isManualState = record[IS_MANUAL_STATE] ?: false,
          latestObservedQuantity =
              SeedQuantityModel.of(
                  record[LATEST_OBSERVED_QUANTITY],
                  record[LATEST_OBSERVED_UNITS_ID],
              ),
          latestObservedTime = record[LATEST_OBSERVED_TIME],
          numberOfTrees = record[TREES_COLLECTED_FROM],
          nurseryStartDate = record[NURSERY_START_DATE],
          photoFilenames = record[photoFilenamesField],
          processingMethod = record[PROCESSING_METHOD_ID],
          processingNotes = record[PROCESSING_NOTES],
          processingStaffResponsible = record[PROCESSING_STAFF_RESPONSIBLE],
          processingStartDate = record[PROCESSING_START_DATE],
          receivedDate = record[RECEIVED_DATE],
          remaining = SeedQuantityModel.of(record[REMAINING_QUANTITY], record[REMAINING_UNITS_ID]),
          source = record[DATA_SOURCE_ID],
          sourcePlantOrigin = record[SOURCE_PLANT_ORIGIN_ID],
          species = record[species.SCIENTIFIC_NAME],
          speciesCommonName = record[species.COMMON_NAME],
          speciesId = record[SPECIES_ID],
          state = record[STATE_ID]!!,
          storageCondition = record[storageLocations.CONDITION_ID],
          storageLocation = record[storageLocations.NAME],
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
          withdrawals = record[withdrawalsField],
      )
    }
  }

  fun create(accession: AccessionModel): AccessionModel {
    val facilityId =
        accession.facilityId ?: throw IllegalArgumentException("No facility ID specified")
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)
    val state =
        when {
          !accession.isManualState -> AccessionState.AwaitingCheckIn
          accession.state == null -> AccessionState.AwaitingCheckIn
          accession.state == AccessionState.UsedUp ->
              throw IllegalArgumentException("Accessions cannot be set to Used Up at creation time")
          accession.state.isV2Compatible -> accession.state
          else -> throw IllegalArgumentException("Initial state must be v2-compatible")
        }
    val checkedInTime =
        if (state != AccessionState.AwaitingCheckIn) {
          clock.instant()
        } else {
          null
        }
    val estimatedWeight =
        if (accession.remaining?.units != SeedQuantityUnits.Seeds) {
          accession.remaining
        } else {
          null
        }

    requirePermissions {
      createAccession(facilityId)
      accession.speciesId?.let { readSpecies(it) }
    }

    if (parentStore.getFacilityType(facilityId) != FacilityType.SeedBank) {
      throw FacilityTypeMismatchException(facilityId, FacilityType.SeedBank)
    }

    var attemptsRemaining = if (accession.accessionNumber != null) 1 else ACCESSION_NUMBER_RETRIES

    while (attemptsRemaining-- > 0) {
      val accessionNumber =
          accession.accessionNumber
              ?: identifierGenerator.generateIdentifier(organizationId, IdentifierType.ACCESSION)

      try {
        val accessionId =
            dslContext.transactionResult { _ ->
              val accessionId =
                  with(ACCESSIONS) {
                    dslContext
                        .insertInto(ACCESSIONS)
                        .set(CHECKED_IN_TIME, checkedInTime)
                        .set(COLLECTED_DATE, accession.collectedDate)
                        .set(COLLECTION_SITE_CITY, accession.collectionSiteCity)
                        .set(COLLECTION_SITE_COUNTRY_CODE, accession.collectionSiteCountryCode)
                        .set(
                            COLLECTION_SITE_COUNTRY_SUBDIVISION,
                            accession.collectionSiteCountrySubdivision)
                        .set(COLLECTION_SITE_LANDOWNER, accession.collectionSiteLandowner)
                        .set(COLLECTION_SITE_NAME, accession.collectionSiteName)
                        .set(COLLECTION_SITE_NOTES, accession.collectionSiteNotes)
                        .set(COLLECTION_SOURCE_ID, accession.collectionSource)
                        .set(CREATED_BY, currentUser().userId)
                        .set(CREATED_TIME, clock.instant())
                        .set(DATA_SOURCE_ID, accession.source ?: DataSource.Web)
                        .set(
                            EST_SEED_COUNT,
                            accession.calculateEstimatedSeedCount(accession.remaining))
                        .set(EST_WEIGHT_GRAMS, estimatedWeight?.grams)
                        .set(EST_WEIGHT_QUANTITY, estimatedWeight?.quantity)
                        .set(EST_WEIGHT_UNITS_ID, estimatedWeight?.units)
                        .set(FACILITY_ID, facilityId)
                        .set(FIELD_NOTES, accession.fieldNotes)
                        .set(FOUNDER_ID, accession.founderId)
                        .set(IS_MANUAL_STATE, if (accession.isManualState) true else null)
                        .set(MODIFIED_BY, currentUser().userId)
                        .set(MODIFIED_TIME, clock.instant())
                        .set(NUMBER, accessionNumber)
                        .set(NURSERY_START_DATE, accession.nurseryStartDate)
                        .set(PROCESSING_NOTES, accession.processingNotes)
                        .set(RECEIVED_DATE, accession.receivedDate)
                        .set(REMAINING_GRAMS, accession.remaining?.grams)
                        .set(REMAINING_QUANTITY, accession.remaining?.quantity)
                        .set(REMAINING_UNITS_ID, accession.remaining?.units)
                        .set(SOURCE_PLANT_ORIGIN_ID, accession.sourcePlantOrigin)
                        .set(SPECIES_ID, accession.speciesId)
                        .set(STATE_ID, state)
                        .set(
                            STORAGE_LOCATION_ID,
                            getStorageLocationId(facilityId, accession.storageLocation))
                        .set(STORAGE_NOTES, accession.storageNotes)
                        .set(STORAGE_PACKETS, accession.storagePackets)
                        .set(STORAGE_STAFF_RESPONSIBLE, accession.storageStaffResponsible)
                        .set(STORAGE_START_DATE, accession.storageStartDate)
                        .set(TOTAL_GRAMS, accession.total?.grams)
                        .set(TOTAL_QUANTITY, accession.total?.quantity)
                        .set(TOTAL_UNITS_ID, accession.total?.units)
                        .set(TOTAL_VIABILITY_PERCENT, accession.totalViabilityPercent)
                        .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
                        .returning(ID)
                        .fetchOne()
                        ?.get(ID)!!
                  }

              if (accession.remaining != null) {
                with(ACCESSION_QUANTITY_HISTORY) {
                  dslContext
                      .insertInto(ACCESSION_QUANTITY_HISTORY)
                      .set(ACCESSION_ID, accessionId)
                      .set(CREATED_BY, currentUser().userId)
                      .set(CREATED_TIME, clock.instant())
                      .set(HISTORY_TYPE_ID, AccessionQuantityHistoryType.Observed)
                      .set(REMAINING_QUANTITY, accession.remaining.quantity)
                      .set(REMAINING_UNITS_ID, accession.remaining.units)
                      .execute()
                }
              }

              with(ACCESSION_STATE_HISTORY) {
                dslContext
                    .insertInto(ACCESSION_STATE_HISTORY)
                    .set(ACCESSION_ID, accessionId)
                    .set(REASON, "Accession created")
                    .set(NEW_STATE_ID, state)
                    .set(UPDATED_BY, currentUser().userId)
                    .set(UPDATED_TIME, clock.instant())
                    .execute()
              }

              updateCollectors(accessionId, emptyList(), accession.collectors)
              bagStore.updateBags(accessionId, emptySet(), accession.bagNumbers)
              geolocationStore.updateGeolocations(accessionId, emptySet(), accession.geolocations)
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

    if (updated.isManualState && updated.state?.isV2Compatible != true) {
      throw IllegalArgumentException("State must be v2-compatible")
    }

    if (facilityId != existingFacilityId &&
        organizationId != parentStore.getOrganizationId(existingFacilityId)) {
      throw FacilityNotFoundException(facilityId)
    }

    requirePermissions {
      updateAccession(accessionId)
      updated.speciesId?.let { readSpecies(it) }
    }

    // The checked-in time is needed as an input for non-manual state calculations, but can't be
    // directly set on update. For manual states, a transition out of Awaiting Check-In should
    // cause the checked-in time to get set so that if the accession is switched back to
    // non-manual mode, we treat it as already having been checked in.
    val checkedInTime =
        when {
          existing.checkedInTime != null -> existing.checkedInTime
          !updated.isManualState -> null
          updated.state == AccessionState.AwaitingCheckIn -> null
          else -> clock.instant()
        }

    val updatedWithCheckedInTime = updated.copy(checkedInTime = checkedInTime)

    val accession = updatedWithCheckedInTime.withCalculatedValues(clock, existing)
    val todayLocal = LocalDate.now(clock)

    if (accession.storageStartDate?.isAfter(todayLocal) == true) {
      throw IllegalArgumentException("Storage start date may not be in the future")
    }

    if (accession.subsetWeightQuantity?.units == SeedQuantityUnits.Seeds) {
      throw IllegalArgumentException("Subset weight must be a weight measurement, not a seed count")
    }

    dslContext.transaction { _ ->
      if (existing.collectors != accession.collectors) {
        updateCollectors(accessionId, existing.collectors, accession.collectors)
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
      viabilityTestStore.updateViabilityTests(accessionId, existingTests, viabilityTests)
      withdrawalStore.updateWithdrawals(accessionId, existing.withdrawals, withdrawals)

      insertQuantityHistory(existing, accession)
      insertStateHistory(existing, accession)

      val rowsUpdated =
          with(ACCESSIONS) {
            dslContext
                .update(ACCESSIONS)
                .set(CHECKED_IN_TIME, accession.checkedInTime)
                .set(COLLECTED_DATE, accession.collectedDate)
                .set(COLLECTION_SITE_CITY, accession.collectionSiteCity)
                .set(COLLECTION_SITE_COUNTRY_CODE, accession.collectionSiteCountryCode)
                .set(
                    COLLECTION_SITE_COUNTRY_SUBDIVISION, accession.collectionSiteCountrySubdivision)
                .set(COLLECTION_SITE_LANDOWNER, accession.collectionSiteLandowner)
                .set(COLLECTION_SITE_NAME, accession.collectionSiteName)
                .set(COLLECTION_SITE_NOTES, accession.collectionSiteNotes)
                .set(COLLECTION_SOURCE_ID, accession.collectionSource)
                .set(DRYING_END_DATE, accession.dryingEndDate)
                .set(DRYING_MOVE_DATE, accession.dryingMoveDate)
                .set(DRYING_START_DATE, accession.dryingStartDate)
                .set(EST_SEED_COUNT, accession.estimatedSeedCount)
                .set(EST_WEIGHT_GRAMS, accession.estimatedWeight?.grams)
                .set(EST_WEIGHT_QUANTITY, accession.estimatedWeight?.quantity)
                .set(EST_WEIGHT_UNITS_ID, accession.estimatedWeight?.units)
                .set(FACILITY_ID, facilityId)
                .set(FIELD_NOTES, accession.fieldNotes)
                .set(FOUNDER_ID, accession.founderId)
                .set(IS_MANUAL_STATE, if (accession.isManualState) true else null)
                .set(LATEST_OBSERVED_QUANTITY, accession.latestObservedQuantity?.quantity)
                .set(LATEST_OBSERVED_TIME, accession.latestObservedTime)
                .set(LATEST_OBSERVED_UNITS_ID, accession.latestObservedQuantity?.units)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .set(NURSERY_START_DATE, accession.nurseryStartDate)
                .set(PROCESSING_METHOD_ID, accession.processingMethod)
                .set(PROCESSING_NOTES, accession.processingNotes)
                .set(PROCESSING_STAFF_RESPONSIBLE, accession.processingStaffResponsible)
                .set(PROCESSING_START_DATE, accession.processingStartDate)
                .set(RECEIVED_DATE, accession.receivedDate)
                .set(REMAINING_GRAMS, accession.remaining?.grams)
                .set(REMAINING_QUANTITY, accession.remaining?.quantity)
                .set(REMAINING_UNITS_ID, accession.remaining?.units)
                .set(SOURCE_PLANT_ORIGIN_ID, accession.sourcePlantOrigin)
                .set(SPECIES_ID, accession.speciesId)
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

    dslContext.transaction { _ ->
      // Child tables will be cleaned up by integrity constraints, except for accession_photos;
      // that one needs to be cleared out beforehand since the actual photo files need to be
      // removed from the file store.
      dslContext.deleteFrom(ACCESSIONS).where(ACCESSIONS.ID.eq(accessionId)).execute()
    }
  }

  fun fetchHistory(accessionId: AccessionId): List<AccessionHistoryModel> {
    requirePermissions { readAccession(accessionId) }

    return (fetchQuantityObservations(accessionId) +
            fetchStateHistory(accessionId) +
            withdrawalStore.fetchHistory(accessionId))
        .sorted()
  }

  private fun fetchStateHistory(accessionId: AccessionId): MutableList<AccessionHistoryModel> {
    return dslContext
        .select(
            ACCESSION_STATE_HISTORY.NEW_STATE_ID,
            ACCESSION_STATE_HISTORY.OLD_STATE_ID,
            ACCESSION_STATE_HISTORY.UPDATED_BY,
            ACCESSION_STATE_HISTORY.UPDATED_TIME,
            USERS.FIRST_NAME,
            USERS.LAST_NAME)
        .from(ACCESSION_STATE_HISTORY)
        .join(USERS)
        .on(ACCESSION_STATE_HISTORY.UPDATED_BY.eq(USERS.ID))
        .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(accessionId))
        .fetch { record ->
          val updatedTime = record[ACCESSION_STATE_HISTORY.UPDATED_TIME]!!
          val date = LocalDate.ofInstant(updatedTime, ZoneOffset.UTC)
          val newState = record[ACCESSION_STATE_HISTORY.NEW_STATE_ID]!!
          val oldState = record[ACCESSION_STATE_HISTORY.OLD_STATE_ID]
          val userId = record[ACCESSION_STATE_HISTORY.UPDATED_BY]!!
          val fullName =
              IndividualUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME])

          if (oldState == null) {
            AccessionHistoryModel(
                createdTime = updatedTime,
                date = date,
                description = messages.historyAccessionCreated(),
                fullName = fullName,
                type = AccessionHistoryType.Created,
                userId = userId,
            )
          } else {
            AccessionHistoryModel(
                createdTime = updatedTime,
                date = date,
                description = messages.historyAccessionStateChanged(newState),
                fullName = fullName,
                type = AccessionHistoryType.StateChanged,
                userId = userId,
            )
          }
        }
  }

  private fun fetchQuantityObservations(accessionId: AccessionId): List<AccessionHistoryModel> {
    return dslContext
        .select(
            ACCESSION_QUANTITY_HISTORY.CREATED_BY,
            ACCESSION_QUANTITY_HISTORY.CREATED_TIME,
            ACCESSION_QUANTITY_HISTORY.REMAINING_QUANTITY,
            ACCESSION_QUANTITY_HISTORY.REMAINING_UNITS_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME)
        .from(ACCESSION_QUANTITY_HISTORY)
        .join(USERS)
        .on(ACCESSION_QUANTITY_HISTORY.CREATED_BY.eq(USERS.ID))
        .where(ACCESSION_QUANTITY_HISTORY.ACCESSION_ID.eq(accessionId))
        .and(ACCESSION_QUANTITY_HISTORY.HISTORY_TYPE_ID.eq(AccessionQuantityHistoryType.Observed))
        .fetch { record ->
          val createdTime = record[ACCESSION_QUANTITY_HISTORY.CREATED_TIME]!!
          val date = LocalDate.ofInstant(createdTime, ZoneOffset.UTC)
          val fullName =
              IndividualUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME])
          val remainingQuantity =
              SeedQuantityModel(
                  record[ACCESSION_QUANTITY_HISTORY.REMAINING_QUANTITY]!!,
                  record[ACCESSION_QUANTITY_HISTORY.REMAINING_UNITS_ID]!!)
          val userId = record[ACCESSION_QUANTITY_HISTORY.CREATED_BY]!!

          AccessionHistoryModel(
              createdTime = createdTime,
              date = date,
              description = messages.historyAccessionQuantityUpdated(remainingQuantity),
              fullName = fullName,
              type = AccessionHistoryType.QuantityUpdated,
              userId = userId,
          )
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
              .set(UPDATED_BY, currentUser().userId)
              .set(UPDATED_TIME, clock.instant())
              .execute()
        }
      }
    }
  }

  /** Records a history entry for a change in remaining quantity. */
  private fun insertQuantityHistory(before: AccessionModel, after: AccessionModel) {
    if (after.remaining != null && before.remaining != after.remaining) {
      val historyType =
          if (before.latestObservedQuantity != after.latestObservedQuantity ||
              before.latestObservedTime != after.latestObservedTime) {
            AccessionQuantityHistoryType.Observed
          } else {
            AccessionQuantityHistoryType.Computed
          }

      with(ACCESSION_QUANTITY_HISTORY) {
        dslContext
            .insertInto(ACCESSION_QUANTITY_HISTORY)
            .set(ACCESSION_ID, before.id)
            .set(HISTORY_TYPE_ID, historyType)
            .set(CREATED_BY, currentUser().userId)
            .set(CREATED_TIME, clock.instant())
            .set(REMAINING_QUANTITY, after.remaining.quantity)
            .set(REMAINING_UNITS_ID, after.remaining.units)
            .execute()
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

    if (accession.state != AccessionState.AwaitingCheckIn) {
      log.info("Accession $accessionId is already checked in; ignoring request to check in again")
      return accession
    }

    // Don't record the time with sub-second precision; it is not useful and makes exact searches
    // problematic in the face of systems with different levels of precision in their native time
    // representations.
    val checkedInTime = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    // V1 COMPATIBILITY: Set checkedInTime as well as state. For v2 accessions, "check in" is just
    // "set the state to AwaitingProcessing" but for v1 accessions, checkedInTime needs to be
    // present. Setting both will do the right thing whether this is a v1 or a v2 accession: the v1
    // path will recalculate the state, ignoring the value we set here, and the v2 path will ignore
    // checkedInTime and use the state we set here.
    val withCheckedInTime =
        accession
            .copy(checkedInTime = checkedInTime, state = AccessionState.AwaitingProcessing)
            .withCalculatedValues(clock, accession)

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

  private fun collectorsMultiset(): Field<List<String>> {
    return DSL.multiset(
            DSL.select(ACCESSION_COLLECTORS.NAME)
                .from(ACCESSION_COLLECTORS)
                .where(ACCESSION_COLLECTORS.ACCESSION_ID.eq(ACCESSIONS.ID))
                .orderBy(ACCESSION_COLLECTORS.POSITION))
        .convertFrom { result -> result.map { it.value1() } }
  }

  private fun updateCollectors(
      accessionId: AccessionId,
      existing: List<String>,
      desired: List<String>
  ) {
    if (existing.size > desired.size) {
      dslContext
          .deleteFrom(ACCESSION_COLLECTORS)
          .where(ACCESSION_COLLECTORS.ACCESSION_ID.eq(accessionId))
          .and(ACCESSION_COLLECTORS.POSITION.ge(desired.size))
          .execute()
    }

    desired.forEachIndexed { position, name ->
      if (position >= existing.size) {
        dslContext
            .insertInto(ACCESSION_COLLECTORS)
            .set(ACCESSION_COLLECTORS.ACCESSION_ID, accessionId)
            .set(ACCESSION_COLLECTORS.POSITION, position)
            .set(ACCESSION_COLLECTORS.NAME, name)
            .execute()
      } else if (name != existing[position]) {
        dslContext
            .update(ACCESSION_COLLECTORS)
            .set(ACCESSION_COLLECTORS.NAME, name)
            .where(ACCESSION_COLLECTORS.ACCESSION_ID.eq(accessionId))
            .and(ACCESSION_COLLECTORS.POSITION.eq(position))
            .execute()
      }
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

  fun countByState(facilityId: FacilityId): Map<AccessionState, Int> {
    requirePermissions { readFacility(facilityId) }

    return countByState(ACCESSIONS.FACILITY_ID.eq(facilityId))
  }

  fun countByState(organizationId: OrganizationId): Map<AccessionState, Int> {
    requirePermissions { readOrganization(organizationId) }

    return countByState(ACCESSIONS.facilities.ORGANIZATION_ID.eq(organizationId))
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

  private fun countByState(condition: Condition): Map<AccessionState, Int> {
    val query =
        dslContext
            .select(ACCESSIONS.STATE_ID, DSL.count())
            .from(ACCESSIONS)
            .where(condition)
            .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues))
            .groupBy(ACCESSIONS.STATE_ID)

    val sql = query.getSQL(ParamType.INLINED)

    val totals =
        log.debugWithTiming("Accession state summary query: $sql") {
          query.fetchMap(ACCESSIONS.STATE_ID, DSL.count())
        }

    // The query results won't include states with no accessions, but we want to return the full
    // list with counts of 0.
    return AccessionState.activeValues.associateWith { totals[it] ?: 0 }
  }

  fun getSummaryStatistics(facilityId: FacilityId): AccessionSummaryStatistics {
    requirePermissions { readFacility(facilityId) }

    return getSummaryStatistics(
        DSL.select(ACCESSIONS.ID)
            .from(ACCESSIONS)
            .where(ACCESSIONS.FACILITY_ID.eq(facilityId))
            .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues)))
  }

  fun getSummaryStatistics(organizationId: OrganizationId): AccessionSummaryStatistics {
    requirePermissions { readOrganization(organizationId) }

    return getSummaryStatistics(
        DSL.select(ACCESSIONS.ID)
            .from(ACCESSIONS)
            .where(ACCESSIONS.facilities.ORGANIZATION_ID.eq(organizationId))
            .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues)))
  }

  fun getSummaryStatistics(subquery: Select<Record1<AccessionId?>>): AccessionSummaryStatistics {
    val seedsRemaining =
        DSL.sum(
            DSL.case_()
                .`when`(
                    ACCESSIONS.REMAINING_UNITS_ID.eq(SeedQuantityUnits.Seeds),
                    ACCESSIONS.REMAINING_QUANTITY)
                .else_(BigDecimal.ZERO))

    val estimatedSeedsRemaining =
        DSL.sum(
            DSL.case_()
                .`when`(
                    ACCESSIONS.REMAINING_UNITS_ID.ne(SeedQuantityUnits.Seeds)
                        .and(ACCESSIONS.SUBSET_COUNT.isNotNull)
                        .and(ACCESSIONS.SUBSET_WEIGHT_GRAMS.isNotNull)
                        .and(ACCESSIONS.REMAINING_GRAMS.isNotNull),
                    ACCESSIONS.REMAINING_GRAMS.div(ACCESSIONS.SUBSET_WEIGHT_GRAMS)
                        .mul(ACCESSIONS.SUBSET_COUNT))
                .else_(BigDecimal.ZERO))

    val unknownQuantity =
        DSL.sum(
            DSL.case_()
                .`when`(
                    ACCESSIONS.REMAINING_UNITS_ID.ne(SeedQuantityUnits.Seeds)
                        .and(
                            ACCESSIONS.SUBSET_COUNT.isNull
                                .or(ACCESSIONS.SUBSET_WEIGHT_GRAMS.isNull)
                                .or(ACCESSIONS.REMAINING_GRAMS.isNull)),
                    1)
                .else_(0))

    val query =
        dslContext
            .select(
                DSL.countDistinct(ACCESSIONS.ID),
                DSL.countDistinct(ACCESSIONS.SPECIES_ID),
                seedsRemaining,
                estimatedSeedsRemaining,
                unknownQuantity)
            .from(ACCESSIONS)
            .where(ACCESSIONS.ID.`in`(subquery))

    val stats =
        log.debugWithTiming("Summary statistics query: ${query.getSQL(ParamType.INLINED)}") {
          query.fetchOne {
              (
                  accessions,
                  species,
                  subtotalBySeedCount,
                  subtotalByWeightEstimate,
                  unknownQuantityAccessions,
              ) ->
            AccessionSummaryStatistics(
                accessions ?: 0,
                species ?: 0,
                subtotalBySeedCount ?: BigDecimal.ZERO,
                subtotalByWeightEstimate ?: BigDecimal.ZERO,
                unknownQuantityAccessions ?: BigDecimal.ZERO,
            )
          }
        }

    return stats ?: throw IllegalStateException("Unable to calculate statistics")
  }
}
