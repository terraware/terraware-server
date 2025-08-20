package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AccessionSpeciesHasDeliveriesException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.IdentifierType
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_COLLECTORS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_QUANTITY_HISTORY
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.event.AccessionSpeciesChangedEvent
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.AccessionSummaryStatistics
import com.terraformation.backend.seedbank.model.AccessionUpdateContext
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.activeValues
import com.terraformation.backend.seedbank.model.isV2Compatible
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record1
import org.jooq.Select
import org.jooq.conf.ParamType
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

@Named
class AccessionStore(
    private val dslContext: DSLContext,
    private val bagStore: BagStore,
    private val facilitiesDao: FacilitiesDao,
    private val geolocationStore: GeolocationStore,
    private val viabilityTestStore: ViabilityTestStore,
    private val parentStore: ParentStore,
    private val withdrawalStore: WithdrawalStore,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
    private val messages: Messages,
    private val identifierGenerator: IdentifierGenerator,
) {
  companion object {
    /** Number of times to try generating a unique accession number before giving up. */
    private const val ACCESSION_NUMBER_RETRIES = 10
  }

  private val log = perClassLogger()

  private val hasDeliveriesField =
      DSL.field(
          DSL.exists(
              DSL.selectOne()
                  .from(BATCHES)
                  .join(BATCH_WITHDRAWALS)
                  .on(BATCHES.ID.eq(BATCH_WITHDRAWALS.BATCH_ID))
                  .join(DELIVERIES)
                  .on(BATCH_WITHDRAWALS.WITHDRAWAL_ID.eq(DELIVERIES.WITHDRAWAL_ID))
                  .where(BATCHES.ACCESSION_ID.eq(ACCESSIONS.ID))
          )
      )

  fun fetchOneById(accessionId: AccessionId): AccessionModel {
    requirePermissions { readAccession(accessionId) }

    return fetchOneByCondition(ACCESSIONS.ID.eq(accessionId))
        ?: throw AccessionNotFoundException(accessionId)
  }

  fun fetchOneByNumber(facilityId: FacilityId, accessionNumber: String): AccessionModel? {
    val model =
        fetchOneByCondition(
            ACCESSIONS.FACILITY_ID.eq(facilityId).and(ACCESSIONS.NUMBER.eq(accessionNumber))
        )

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
                ACCESSIONS.subLocations.NAME,
                bagNumbersField,
                geolocationsField,
                hasDeliveriesField,
                photoFilenamesField,
                collectorsField,
                viabilityTestsField,
                withdrawalsField,
            )
            .from(ACCESSIONS)
            .where(condition)
            .fetchOne() ?: return null

    return with(ACCESSIONS) {
      AccessionModel(
          id = record[ID],
          accessionNumber = record[NUMBER],
          bagNumbers = record[bagNumbersField],
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
          estimatedSeedCount = record[EST_SEED_COUNT],
          estimatedWeight =
              SeedQuantityModel.of(record[EST_WEIGHT_QUANTITY], record[EST_WEIGHT_UNITS_ID]),
          facilityId = record[FACILITY_ID],
          founderId = record[FOUNDER_ID],
          geolocations = record[geolocationsField],
          hasDeliveries = record[hasDeliveriesField],
          latestObservedQuantity =
              SeedQuantityModel.of(
                  record[LATEST_OBSERVED_QUANTITY],
                  record[LATEST_OBSERVED_UNITS_ID],
              ),
          latestObservedTime = record[LATEST_OBSERVED_TIME],
          numberOfTrees = record[TREES_COLLECTED_FROM],
          photoFilenames = record[photoFilenamesField],
          processingNotes = record[PROCESSING_NOTES],
          projectId = record[PROJECT_ID],
          receivedDate = record[RECEIVED_DATE],
          remaining = SeedQuantityModel.of(record[REMAINING_QUANTITY], record[REMAINING_UNITS_ID]),
          source = record[DATA_SOURCE_ID],
          species = record[species.SCIENTIFIC_NAME],
          speciesCommonName = record[species.COMMON_NAME],
          speciesId = record[SPECIES_ID],
          state = record[STATE_ID]!!,
          subLocation = record[subLocations.NAME],
          subsetCount = record[SUBSET_COUNT],
          subsetWeightQuantity =
              SeedQuantityModel.of(
                  record[SUBSET_WEIGHT_QUANTITY],
                  record[SUBSET_WEIGHT_UNITS_ID],
              ),
          totalViabilityPercent = record[TOTAL_VIABILITY_PERCENT],
          totalWithdrawnCount = record[TOTAL_WITHDRAWN_COUNT],
          totalWithdrawnWeight =
              SeedQuantityModel.of(
                  record[TOTAL_WITHDRAWN_WEIGHT_QUANTITY],
                  record[TOTAL_WITHDRAWN_WEIGHT_UNITS_ID],
              ),
          viabilityTests = record[viabilityTestsField],
          withdrawals = record[withdrawalsField],
          clock = getClock(record[ID]!!),
      )
    }
  }

  fun create(accession: AccessionModel): AccessionModel {
    val facilityId =
        accession.facilityId ?: throw IllegalArgumentException("No facility ID specified")
    val facility =
        facilitiesDao.fetchOneById(facilityId) ?: throw FacilityNotFoundException(facilityId)
    val organizationId = facility.organizationId!!
    val state =
        when {
          accession.state.isV2Compatible -> accession.state
          else -> throw IllegalArgumentException("Initial state must be v2-compatible")
        }
    val estimatedWeight =
        if (accession.remaining?.units != SeedQuantityUnits.Seeds) {
          accession.remaining
        } else {
          null
        }

    requirePermissions {
      createAccession(facilityId)
      accession.projectId?.let { readProject(it) }
      accession.speciesId?.let { readSpecies(it) }
    }

    if (
        accession.state == AccessionState.UsedUp &&
            (accession.remaining == null || accession.remaining.quantity.signum() != 0)
    ) {
      throw IllegalArgumentException("Quantity must be zero if state is UsedUp")
    }

    if (facility.typeId != FacilityType.SeedBank) {
      throw FacilityTypeMismatchException(facilityId, FacilityType.SeedBank)
    }

    if (
        accession.projectId != null &&
            organizationId != parentStore.getOrganizationId(accession.projectId)
    ) {
      throw ProjectInDifferentOrganizationException()
    }

    var attemptsRemaining = if (accession.accessionNumber != null) 1 else ACCESSION_NUMBER_RETRIES

    while (attemptsRemaining-- > 0) {
      val accessionNumber =
          accession.accessionNumber
              ?: identifierGenerator.generateTextIdentifier(
                  organizationId,
                  IdentifierType.ACCESSION,
                  facility.facilityNumber!!,
              )

      try {
        val accessionId =
            dslContext.transactionResult { _ ->
              val accessionId =
                  with(ACCESSIONS) {
                    dslContext
                        .insertInto(ACCESSIONS)
                        .set(COLLECTED_DATE, accession.collectedDate)
                        .set(COLLECTION_SITE_CITY, accession.collectionSiteCity)
                        .set(COLLECTION_SITE_COUNTRY_CODE, accession.collectionSiteCountryCode)
                        .set(
                            COLLECTION_SITE_COUNTRY_SUBDIVISION,
                            accession.collectionSiteCountrySubdivision,
                        )
                        .set(COLLECTION_SITE_LANDOWNER, accession.collectionSiteLandowner)
                        .set(COLLECTION_SITE_NAME, accession.collectionSiteName)
                        .set(COLLECTION_SITE_NOTES, accession.collectionSiteNotes)
                        .set(COLLECTION_SOURCE_ID, accession.collectionSource)
                        .set(CREATED_BY, currentUser().userId)
                        .set(CREATED_TIME, clock.instant())
                        .set(DATA_SOURCE_ID, accession.source ?: DataSource.Web)
                        .set(
                            EST_SEED_COUNT,
                            accession.calculateEstimatedSeedCount(accession.remaining),
                        )
                        .set(EST_WEIGHT_GRAMS, estimatedWeight?.grams)
                        .set(EST_WEIGHT_QUANTITY, estimatedWeight?.quantity)
                        .set(EST_WEIGHT_UNITS_ID, estimatedWeight?.units)
                        .set(FACILITY_ID, facilityId)
                        .set(FOUNDER_ID, accession.founderId)
                        .set(MODIFIED_BY, currentUser().userId)
                        .set(MODIFIED_TIME, clock.instant())
                        .set(NUMBER, accessionNumber)
                        .set(PROCESSING_NOTES, accession.processingNotes)
                        .set(PROJECT_ID, accession.projectId)
                        .set(RECEIVED_DATE, accession.receivedDate)
                        .set(REMAINING_GRAMS, accession.remaining?.grams)
                        .set(REMAINING_QUANTITY, accession.remaining?.quantity)
                        .set(REMAINING_UNITS_ID, accession.remaining?.units)
                        .set(SPECIES_ID, accession.speciesId)
                        .set(STATE_ID, state)
                        .set(SUB_LOCATION_ID, getSubLocationId(facilityId, accession.subLocation))
                        .set(TOTAL_VIABILITY_PERCENT, accession.totalViabilityPercent)
                        .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
                        .returning(ID)
                        .fetchOne(ID)!!
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
                  accessionId,
                  emptyList(),
                  accession.viabilityTests,
              )
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

  fun update(updated: AccessionModel, updateContext: AccessionUpdateContext? = null) {
    val accessionId = updated.id ?: throw IllegalArgumentException("No accession ID specified")

    requirePermissions { updateAccession(accessionId) }

    val existing = fetchOneById(accessionId)
    val existingFacilityId =
        existing.facilityId ?: throw IllegalStateException("Accession has no facility ID")
    val facilityId = updated.facilityId ?: existing.facilityId
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    if (!updated.state.isV2Compatible) {
      throw IllegalArgumentException("State must be v2-compatible")
    }

    if (
        facilityId != existingFacilityId &&
            organizationId != parentStore.getOrganizationId(existingFacilityId)
    ) {
      throw FacilityNotFoundException(facilityId)
    }

    if (existing.hasDeliveries && existing.speciesId != updated.speciesId) {
      throw AccessionSpeciesHasDeliveriesException(accessionId)
    }

    requirePermissions {
      updateAccession(accessionId)
      updated.projectId?.let { readProject(it) }
      updated.speciesId?.let { readSpecies(it) }
    }

    if (
        updated.projectId != null &&
            organizationId != parentStore.getOrganizationId(updated.projectId)
    ) {
      throw ProjectInDifferentOrganizationException()
    }

    val accession = updated.withCalculatedValues(existing)

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
            } ?: withdrawal
          }

      val viabilityTests = withdrawals.mapNotNull { it.viabilityTest }

      bagStore.updateBags(accessionId, existing.bagNumbers, accession.bagNumbers)
      geolocationStore.updateGeolocations(
          accessionId,
          existing.geolocations,
          accession.geolocations,
      )
      viabilityTestStore.updateViabilityTests(accessionId, existingTests, viabilityTests)
      withdrawalStore.updateWithdrawals(accessionId, existing.withdrawals, withdrawals)

      insertQuantityHistory(existing, accession, updateContext?.remainingQuantityNotes)
      insertStateHistory(existing, accession)

      val rowsUpdated =
          with(ACCESSIONS) {
            dslContext
                .update(ACCESSIONS)
                .set(COLLECTED_DATE, accession.collectedDate)
                .set(COLLECTION_SITE_CITY, accession.collectionSiteCity)
                .set(COLLECTION_SITE_COUNTRY_CODE, accession.collectionSiteCountryCode)
                .set(
                    COLLECTION_SITE_COUNTRY_SUBDIVISION,
                    accession.collectionSiteCountrySubdivision,
                )
                .set(COLLECTION_SITE_LANDOWNER, accession.collectionSiteLandowner)
                .set(COLLECTION_SITE_NAME, accession.collectionSiteName)
                .set(COLLECTION_SITE_NOTES, accession.collectionSiteNotes)
                .set(COLLECTION_SOURCE_ID, accession.collectionSource)
                .set(DRYING_END_DATE, accession.dryingEndDate)
                .set(EST_SEED_COUNT, accession.estimatedSeedCount)
                .set(EST_WEIGHT_GRAMS, accession.estimatedWeight?.grams)
                .set(EST_WEIGHT_QUANTITY, accession.estimatedWeight?.quantity)
                .set(EST_WEIGHT_UNITS_ID, accession.estimatedWeight?.units)
                .set(FACILITY_ID, facilityId)
                .set(FOUNDER_ID, accession.founderId)
                .set(LATEST_OBSERVED_QUANTITY, accession.latestObservedQuantity?.quantity)
                .set(LATEST_OBSERVED_TIME, accession.latestObservedTime)
                .set(LATEST_OBSERVED_UNITS_ID, accession.latestObservedQuantity?.units)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .set(PROCESSING_NOTES, accession.processingNotes)
                .set(PROJECT_ID, accession.projectId)
                .set(RECEIVED_DATE, accession.receivedDate)
                .set(REMAINING_GRAMS, accession.remaining?.grams)
                .set(REMAINING_QUANTITY, accession.remaining?.quantity)
                .set(REMAINING_UNITS_ID, accession.remaining?.units)
                .set(SPECIES_ID, accession.speciesId)
                .set(STATE_ID, accession.state)
                .set(SUB_LOCATION_ID, getSubLocationId(facilityId, accession.subLocation))
                .set(SUBSET_COUNT, accession.subsetCount)
                .set(SUBSET_WEIGHT_GRAMS, accession.subsetWeightQuantity?.grams)
                .set(SUBSET_WEIGHT_QUANTITY, accession.subsetWeightQuantity?.quantity)
                .set(SUBSET_WEIGHT_UNITS_ID, accession.subsetWeightQuantity?.units)
                .set(TOTAL_VIABILITY_PERCENT, accession.totalViabilityPercent)
                .set(TOTAL_WITHDRAWN_COUNT, accession.totalWithdrawnCount)
                .set(TOTAL_WITHDRAWN_WEIGHT_GRAMS, accession.totalWithdrawnWeight?.grams)
                .set(TOTAL_WITHDRAWN_WEIGHT_QUANTITY, accession.totalWithdrawnWeight?.quantity)
                .set(TOTAL_WITHDRAWN_WEIGHT_UNITS_ID, accession.totalWithdrawnWeight?.units)
                .set(TREES_COLLECTED_FROM, accession.numberOfTrees)
                .where(ID.eq(accessionId))
                .execute()
          }

      if (rowsUpdated != 1) {
        log.error("Accession $accessionId exists in database but update failed")
        throw DataAccessException("Unable to update accession $accessionId")
      }

      if (
          accession.speciesId != null &&
              existing.speciesId != null &&
              accession.speciesId != existing.speciesId
      ) {
        eventPublisher.publishEvent(
            AccessionSpeciesChangedEvent(accessionId, existing.speciesId, accession.speciesId)
        )
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
    val timeZone = parentStore.getEffectiveTimeZone(accessionId)

    return dslContext
        .select(
            ACCESSION_STATE_HISTORY.NEW_STATE_ID,
            ACCESSION_STATE_HISTORY.OLD_STATE_ID,
            ACCESSION_STATE_HISTORY.UPDATED_BY,
            ACCESSION_STATE_HISTORY.UPDATED_TIME,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
        )
        .from(ACCESSION_STATE_HISTORY)
        .join(USERS)
        .on(ACCESSION_STATE_HISTORY.UPDATED_BY.eq(USERS.ID))
        .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(accessionId))
        .fetch { record ->
          val updatedTime = record[ACCESSION_STATE_HISTORY.UPDATED_TIME]!!
          val date = LocalDate.ofInstant(updatedTime, timeZone)
          val newState = record[ACCESSION_STATE_HISTORY.NEW_STATE_ID]!!
          val oldState = record[ACCESSION_STATE_HISTORY.OLD_STATE_ID]
          val userId = record[ACCESSION_STATE_HISTORY.UPDATED_BY]!!
          val fullName =
              TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME])

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
    val timeZone = parentStore.getEffectiveTimeZone(accessionId)

    return dslContext
        .select(
            ACCESSION_QUANTITY_HISTORY.CREATED_BY,
            ACCESSION_QUANTITY_HISTORY.CREATED_TIME,
            ACCESSION_QUANTITY_HISTORY.NOTES,
            ACCESSION_QUANTITY_HISTORY.REMAINING_QUANTITY,
            ACCESSION_QUANTITY_HISTORY.REMAINING_UNITS_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
        )
        .from(ACCESSION_QUANTITY_HISTORY)
        .join(USERS)
        .on(ACCESSION_QUANTITY_HISTORY.CREATED_BY.eq(USERS.ID))
        .where(ACCESSION_QUANTITY_HISTORY.ACCESSION_ID.eq(accessionId))
        .and(ACCESSION_QUANTITY_HISTORY.HISTORY_TYPE_ID.eq(AccessionQuantityHistoryType.Observed))
        .fetch { record ->
          val createdTime = record[ACCESSION_QUANTITY_HISTORY.CREATED_TIME]!!
          val date = LocalDate.ofInstant(createdTime, timeZone)
          val fullName =
              TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME])
          val notes = record[ACCESSION_QUANTITY_HISTORY.NOTES]
          val remainingQuantity =
              SeedQuantityModel(
                  record[ACCESSION_QUANTITY_HISTORY.REMAINING_QUANTITY]!!,
                  record[ACCESSION_QUANTITY_HISTORY.REMAINING_UNITS_ID]!!,
              )
          val userId = record[ACCESSION_QUANTITY_HISTORY.CREATED_BY]!!

          AccessionHistoryModel(
              createdTime = createdTime,
              date = date,
              description = messages.historyAccessionQuantityUpdated(remainingQuantity),
              fullName = fullName,
              notes = notes,
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
      before.getStateTransition(after)?.let { stateTransition ->
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
  private fun insertQuantityHistory(
      before: AccessionModel,
      after: AccessionModel,
      notes: String? = null,
  ) {
    if (after.remaining != null && before.remaining != after.remaining) {
      val historyType =
          if (
              before.latestObservedQuantity != after.latestObservedQuantity ||
                  before.latestObservedTime != after.latestObservedTime
          ) {
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
            .set(NOTES, notes)
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
  fun updateAndFetch(
      accession: AccessionModel,
      updateContext: AccessionUpdateContext? = null,
  ): AccessionModel {
    val accessionId = accession.id ?: throw IllegalArgumentException("Missing accession ID")
    update(accession, updateContext)
    return fetchOneById(accessionId)
  }

  /**
   * Marks an accession as checked in and returns the modified accession data including any computed
   * field values.
   *
   * @throws AccessionNotFoundException The accession did not exist or wasn't accessible by the
   *   current user.
   */
  fun checkIn(accessionId: AccessionId): AccessionModel {
    requirePermissions { updateAccession(accessionId) }

    val accession = fetchOneById(accessionId)

    if (accession.state != AccessionState.AwaitingCheckIn) {
      log.info("Accession $accessionId is already checked in; ignoring request to check in again")
      return accession
    }

    val checkedIn =
        accession.copy(state = AccessionState.AwaitingProcessing).withCalculatedValues(accession)

    dslContext.transaction { _ ->
      val rowsUpdated =
          with(ACCESSIONS) {
            dslContext
                .update(ACCESSIONS)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .set(STATE_ID, checkedIn.state)
                .where(ID.eq(accessionId))
                .and(STATE_ID.eq(AccessionState.AwaitingCheckIn))
                .execute()
          }

      if (rowsUpdated == 1) {
        insertStateHistory(accession, checkedIn)
      }
    }

    return checkedIn
  }

  fun assignProject(projectId: ProjectId, accessionIds: Collection<AccessionId>) {
    requirePermissions { readProject(projectId) }

    if (accessionIds.isEmpty()) {
      return
    }

    val projectOrganizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)
    val hasOtherOrganizationIds =
        dslContext
            .selectOne()
            .from(ACCESSIONS)
            .where(ACCESSIONS.ID.`in`(accessionIds))
            .and(ACCESSIONS.facilities.ORGANIZATION_ID.ne(projectOrganizationId))
            .limit(1)
            .fetch()
    if (hasOtherOrganizationIds.isNotEmpty) {
      throw ProjectInDifferentOrganizationException()
    }

    requirePermissions {
      // All accessions are in the same organization, so it's sufficient to check permissions on
      // just one of them.
      updateAccessionProject(accessionIds.first())
    }

    with(ACCESSIONS) {
      dslContext
          .update(ACCESSIONS)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(PROJECT_ID, projectId)
          .where(ID.`in`(accessionIds))
          .execute()
    }
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
    return accession.withCalculatedValues(existing)
  }

  private fun photoFilenamesMultiset(): Field<List<String>> {
    return DSL.multiset(
            DSL.select(FILES.FILE_NAME)
                .from(ACCESSION_PHOTOS)
                .join(FILES)
                .on(ACCESSION_PHOTOS.FILE_ID.eq(FILES.ID))
                .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(ACCESSIONS.ID))
                .orderBy(FILES.CREATED_TIME)
        )
        .convertFrom { result -> result.map { it.value1() } }
  }

  private fun collectorsMultiset(): Field<List<String>> {
    return DSL.multiset(
            DSL.select(ACCESSION_COLLECTORS.NAME)
                .from(ACCESSION_COLLECTORS)
                .where(ACCESSION_COLLECTORS.ACCESSION_ID.eq(ACCESSIONS.ID))
                .orderBy(ACCESSION_COLLECTORS.POSITION)
        )
        .convertFrom { result -> result.map { it.value1() } }
  }

  private fun updateCollectors(
      accessionId: AccessionId,
      existing: List<String>,
      desired: List<String>,
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

  private fun getSubLocationId(facilityId: FacilityId, name: String?): SubLocationId? {
    return if (name == null) {
      null
    } else {
      dslContext
          .select(SUB_LOCATIONS.ID)
          .from(SUB_LOCATIONS)
          .where(SUB_LOCATIONS.NAME.eq(name))
          .and(SUB_LOCATIONS.FACILITY_ID.eq(facilityId))
          .fetchOne(SUB_LOCATIONS.ID)
          ?: throw IllegalArgumentException("Unable to find sub-location $name")
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

  /**
   * Returns the number of active accessions in each sub-location at a facility, If there are no
   * active accessions in a sub-location, it is not included in the map (that is, the count is never
   * 0).
   */
  fun countActiveBySubLocation(facilityId: FacilityId): Map<SubLocationId, Int> {
    requirePermissions { readFacility(facilityId) }

    val countField = DSL.count()

    return dslContext
        .select(ACCESSIONS.SUB_LOCATION_ID, countField)
        .from(ACCESSIONS)
        .where(ACCESSIONS.FACILITY_ID.eq(facilityId))
        .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues))
        .groupBy(ACCESSIONS.SUB_LOCATION_ID)
        .fetchMap(ACCESSIONS.SUB_LOCATION_ID.asNonNullable(), countField)
  }

  fun countActiveInSubLocation(subLocationId: SubLocationId): Int {
    requirePermissions { readSubLocation(subLocationId) }

    val facilityId = parentStore.getFacilityId(subLocationId)

    return dslContext
        .selectCount()
        .from(ACCESSIONS)
        .where(ACCESSIONS.SUB_LOCATION_ID.eq(subLocationId))
        .and(ACCESSIONS.FACILITY_ID.eq(facilityId))
        .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues))
        .fetchOne()
        ?.value1() ?: 0
  }

  fun fetchDryingEndDue(
      facilityId: FacilityId,
      after: LocalDate,
      until: LocalDate,
  ): Map<String, AccessionId> {
    return with(ACCESSIONS) {
      dslContext
          .select(ID, NUMBER)
          .from(ACCESSIONS)
          .where(STATE_ID.eq(AccessionState.Drying))
          .and(DRYING_END_DATE.le(until))
          .and(DRYING_END_DATE.gt(after))
          .and(FACILITY_ID.eq(facilityId))
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
            .groupBy(ACCESSIONS.STATE_ID)

    val totals = query.fetchMap(ACCESSIONS.STATE_ID, DSL.count())

    // The query results won't include states with no accessions, but we want to return the full
    // list with counts of 0.
    return AccessionState.entries.filter { it.isV2Compatible }.associateWith { totals[it] ?: 0 }
  }

  fun getSummaryStatistics(facilityId: FacilityId): AccessionSummaryStatistics {
    requirePermissions { readFacility(facilityId) }

    return getSummaryStatistics(
        DSL.select(ACCESSIONS.ID)
            .from(ACCESSIONS)
            .where(ACCESSIONS.FACILITY_ID.eq(facilityId))
            .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues))
    )
  }

  fun getSummaryStatistics(organizationId: OrganizationId): AccessionSummaryStatistics {
    requirePermissions { readOrganization(organizationId) }

    return getSummaryStatistics(
        DSL.select(ACCESSIONS.ID)
            .from(ACCESSIONS)
            .where(ACCESSIONS.facilities.ORGANIZATION_ID.eq(organizationId))
            .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues))
    )
  }

  fun getSummaryStatistics(
      facilityId: FacilityId,
      projectId: ProjectId,
  ): AccessionSummaryStatistics {
    requirePermissions { readProject(projectId) }

    return getSummaryStatistics(
        DSL.select(ACCESSIONS.ID)
            .from(ACCESSIONS)
            .where(ACCESSIONS.PROJECT_ID.eq(projectId))
            .and(ACCESSIONS.FACILITY_ID.eq(facilityId))
            .and(ACCESSIONS.STATE_ID.`in`(AccessionState.activeValues))
    )
  }

  fun getSummaryStatistics(subquery: Select<Record1<AccessionId?>>): AccessionSummaryStatistics {
    val seedsRemaining =
        DSL.sum(
            DSL.case_()
                .`when`(
                    ACCESSIONS.REMAINING_UNITS_ID.eq(SeedQuantityUnits.Seeds),
                    ACCESSIONS.REMAINING_QUANTITY,
                )
                .else_(BigDecimal.ZERO)
        )

    val estimatedSeedsRemaining =
        DSL.sum(
            DSL.case_()
                .`when`(
                    ACCESSIONS.REMAINING_UNITS_ID.ne(SeedQuantityUnits.Seeds)
                        .and(ACCESSIONS.SUBSET_COUNT.isNotNull)
                        .and(ACCESSIONS.SUBSET_WEIGHT_GRAMS.isNotNull)
                        .and(ACCESSIONS.REMAINING_GRAMS.isNotNull),
                    ACCESSIONS.REMAINING_GRAMS.div(ACCESSIONS.SUBSET_WEIGHT_GRAMS)
                        .mul(ACCESSIONS.SUBSET_COUNT),
                )
                .else_(BigDecimal.ZERO)
        )

    val unknownQuantity =
        DSL.sum(
            DSL.case_()
                .`when`(
                    ACCESSIONS.REMAINING_UNITS_ID.ne(SeedQuantityUnits.Seeds)
                        .and(
                            ACCESSIONS.SUBSET_COUNT.isNull
                                .or(ACCESSIONS.SUBSET_WEIGHT_GRAMS.isNull)
                                .or(ACCESSIONS.REMAINING_GRAMS.isNull)
                        ),
                    1,
                )
                .else_(0)
        )

    val speciesCount =
        dslContext
            .select(DSL.countDistinct(ACCESSIONS.SPECIES_ID))
            .from(ACCESSIONS)
            .join(SPECIES)
            .on(ACCESSIONS.SPECIES_ID.eq(SPECIES.ID))
            .where(SPECIES.DELETED_TIME.isNull)
            .and(ACCESSIONS.ID.`in`(subquery))
            .fetchOne()
            ?.value1()

    val query =
        dslContext
            .select(
                DSL.countDistinct(ACCESSIONS.ID),
                seedsRemaining,
                estimatedSeedsRemaining,
                DSL.sum(ACCESSIONS.TOTAL_WITHDRAWN_COUNT),
                unknownQuantity,
            )
            .from(ACCESSIONS)
            .where(ACCESSIONS.ID.`in`(subquery))

    val stats =
        log.debugWithTiming("Summary statistics query: ${query.getSQL(ParamType.INLINED)}") {
          query.fetchOne {
              (
                  accessions,
                  subtotalBySeedCount,
                  subtotalByWeightEstimate,
                  seedsWithdrawn,
                  unknownQuantityAccessions,
              ) ->
            AccessionSummaryStatistics(
                accessions ?: 0,
                speciesCount ?: 0,
                subtotalBySeedCount ?: BigDecimal.ZERO,
                subtotalByWeightEstimate ?: BigDecimal.ZERO,
                seedsWithdrawn ?: BigDecimal.ZERO,
                unknownQuantityAccessions ?: BigDecimal.ZERO,
            )
          }
        }

    return stats ?: throw IllegalStateException("Unable to calculate statistics")
  }

  fun getClock(accessionId: AccessionId): Clock {
    return clock.withZone(parentStore.getEffectiveTimeZone(accessionId))
  }

  /**
   * Runs a function on each accession in the database. Usually used for data migrations.
   *
   * Must be called as the system user.
   *
   * Calls [func] in a separate transaction for each accession, with a lock held on the accession's
   * database row to prevent multiple server instances from processing the same accession at the
   * same time.
   *
   * @param condition Query condition that matches accessions that haven't been processed yet.
   * @param func Function that updates an accession as needed. Each invocation of [func] is in its
   *   own database transaction. Should update the accession such that [condition] no longer matches
   *   it. If [func] throws an exception, the migration is aborted, but any changes to other
   *   accessions that were applied by earlier calls to [func] are retained.
   */
  @Suppress("UNUSED")
  fun forEachAccession(condition: Condition, func: (AccessionModel) -> Unit) {
    var nextAccessionId: AccessionId? = null

    if (currentUser().userType != UserType.System) {
      throw AccessDeniedException("Migrations must run as system user")
    }

    do {
      // Walk through accessions one at a time, locking each one since this migration could be
      // running on multiple server instances at once.
      dslContext.transaction { _ ->
        nextAccessionId =
            dslContext
                .select(ACCESSIONS.ID)
                .from(ACCESSIONS)
                .where(condition)
                .and(nextAccessionId?.let { ACCESSIONS.ID.gt(it) } ?: DSL.trueCondition())
                .orderBy(ACCESSIONS.ID)
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOne(ACCESSIONS.ID)

        nextAccessionId?.let { accessionId -> func(fetchOneById(accessionId)) }
      }
    } while (nextAccessionId != null)
  }
}
