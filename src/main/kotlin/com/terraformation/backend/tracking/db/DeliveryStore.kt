package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.daos.DeliveriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingsDao
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_POPULATIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.db.UndoOfUndoNotAllowedException
import com.terraformation.backend.nursery.db.WithdrawalNotFoundException
import com.terraformation.backend.tracking.model.DeliveryModel
import com.terraformation.backend.tracking.model.PlantingModel
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

@Named
class DeliveryStore(
    private val clock: InstantSource,
    private val deliveriesDao: DeliveriesDao,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
    private val plantingsDao: PlantingsDao,
) {
  private val log = perClassLogger()

  private val plantingsMultiset =
      DSL.multiset(
              DSL.select(PLANTINGS.asterisk())
                  .from(PLANTINGS)
                  .where(PLANTINGS.DELIVERY_ID.eq(DELIVERIES.ID))
                  .orderBy(PLANTINGS.ID)
          )
          .convertFrom { result -> result.map { PlantingModel(it) } }

  fun fetchOneById(deliveryId: DeliveryId): DeliveryModel {
    requirePermissions { readDelivery(deliveryId) }

    return dslContext
        .select(DELIVERIES.asterisk(), plantingsMultiset)
        .from(DELIVERIES)
        .where(DELIVERIES.ID.eq(deliveryId))
        .fetchOne { DeliveryModel(it, plantingsMultiset) }
        ?: throw DeliveryNotFoundException(deliveryId)
  }

  fun fetchOneByWithdrawalId(withdrawalId: WithdrawalId): DeliveryModel? {
    val model =
        dslContext
            .select(DELIVERIES.asterisk(), plantingsMultiset)
            .from(DELIVERIES)
            .where(DELIVERIES.WITHDRAWAL_ID.eq(withdrawalId))
            .fetchOne { DeliveryModel(it, plantingsMultiset) }

    return if (model?.id != null && currentUser().canReadDelivery(model.id)) model else null
  }

  fun createDelivery(
      withdrawalId: WithdrawalId,
      plantingSiteId: PlantingSiteId,
      substratumId: SubstratumId? = null,
      quantities: Map<SpeciesId, Int>,
  ): DeliveryId {
    requirePermissions { createDelivery(plantingSiteId) }

    val now = clock.instant()
    val userId = currentUser().userId

    if (substratumId == null && plantingSiteHasSubstrata(plantingSiteId)) {
      throw DeliveryMissingSubstratumException(plantingSiteId)
    }

    val nurseryFacilityId =
        parentStore.getFacilityId(withdrawalId) ?: throw WithdrawalNotFoundException(withdrawalId)
    val nurseryOrganizationId = parentStore.getOrganizationId(nurseryFacilityId)
    val plantingSiteOrganizationId = parentStore.getOrganizationId(plantingSiteId)

    if (nurseryOrganizationId != plantingSiteOrganizationId) {
      throw CrossOrganizationDeliveryNotAllowedException(nurseryFacilityId, plantingSiteId)
    }

    return dslContext.transactionResult { _ ->
      val deliveriesRow =
          DeliveriesRow(
              createdBy = userId,
              createdTime = now,
              modifiedBy = userId,
              modifiedTime = now,
              plantingSiteId = plantingSiteId,
              withdrawalId = withdrawalId,
          )

      deliveriesDao.insert(deliveriesRow)
      val deliveryId = deliveriesRow.id!!

      val plantingIds =
          quantities.map { (speciesId, numPlants) ->
            val plantingsRow =
                PlantingsRow(
                    createdBy = userId,
                    createdTime = now,
                    deliveryId = deliveryId,
                    numPlants = numPlants,
                    plantingSiteId = plantingSiteId,
                    plantingTypeId = PlantingType.Delivery,
                    substratumId = substratumId,
                    speciesId = speciesId,
                )

            plantingsDao.insert(plantingsRow)

            addToPopulations(plantingSiteId, substratumId, speciesId, numPlants)

            plantingsRow.id!!
          }

      log.info(
          "Created delivery $deliveryId to planting site $plantingSiteId plot $substratumId with " +
              "plantings $plantingIds"
      )

      deliveryId
    }
  }

  fun reassignDelivery(deliveryId: DeliveryId, reassignments: List<Reassignment>) {
    requirePermissions { updateDelivery(deliveryId) }

    val now = clock.instant()
    val userId = currentUser().userId

    val plantingSiteId = getPlantingSiteId(deliveryId)
    val originalPlantingIds = reassignments.map { it.fromPlantingId }
    val originalPlantings =
        plantingsDao.fetchById(*originalPlantingIds.toTypedArray()).associateBy { it.id!! }
    val deliveryPlantings = plantingsDao.fetchByDeliveryId(deliveryId)

    if (getWithdrawalPurpose(deliveryId) == WithdrawalPurpose.Undo) {
      throw ReassignmentOfUndoNotAllowedException(deliveryId)
    }

    if (deliveryAlreadyUndone(deliveryId)) {
      throw ReassignmentOfUndoneWithdrawalNotAllowedException(deliveryId)
    }

    val newPlantings =
        reassignments.flatMap { reassignment ->
          val fromPlantingId = reassignment.fromPlantingId

          requirePermissions { readPlanting(fromPlantingId) }

          val originalPlanting =
              originalPlantings[fromPlantingId] ?: throw PlantingNotFoundException(fromPlantingId)
          val speciesId = originalPlanting.speciesId!!

          if (originalPlanting.deliveryId != deliveryId) {
            throw CrossDeliveryReassignmentNotAllowedException(
                fromPlantingId,
                originalPlanting.deliveryId!!,
                deliveryId,
            )
          }

          if (originalPlanting.plantingTypeId != PlantingType.Delivery) {
            throw ReassignmentOfReassignmentNotAllowedException(fromPlantingId)
          }

          // A unique constraint prevents us from having more than one ReassignmentFrom planting
          // of a particular species on a delivery, so there's no need to scan for other
          // reassignments to see if they add up to more than the original planting.
          if (reassignment.numPlants > originalPlanting.numPlants!!) {
            throw ReassignmentTooLargeException(fromPlantingId)
          }

          if (reassignment.toSubstratumId == originalPlanting.substratumId) {
            throw ReassignmentToSamePlotNotAllowedException(fromPlantingId)
          }

          // Unique constraint will catch duplicate reassignments, but we can throw a more precise
          // exception by checking for them explicitly.
          if (deliveryPlantings.any { it.speciesId == speciesId && it.id != fromPlantingId }) {
            throw ReassignmentExistsException(fromPlantingId)
          }

          val skeletonRow =
              PlantingsRow(
                  createdBy = userId,
                  createdTime = now,
                  deliveryId = deliveryId,
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId,
              )

          listOf(
              skeletonRow.copy(
                  numPlants = -reassignment.numPlants,
                  plantingTypeId = PlantingType.ReassignmentFrom,
                  substratumId = originalPlanting.substratumId,
              ),
              skeletonRow.copy(
                  notes = reassignment.notes,
                  numPlants = reassignment.numPlants,
                  plantingTypeId = PlantingType.ReassignmentTo,
                  substratumId = reassignment.toSubstratumId,
              ),
          )
        }

    dslContext.transaction { _ ->
      plantingsDao.insert(newPlantings)

      dslContext
          .update(DELIVERIES)
          .set(DELIVERIES.REASSIGNED_BY, userId)
          .set(DELIVERIES.REASSIGNED_TIME, now)
          .where(DELIVERIES.ID.eq(deliveryId))
          .execute()

      newPlantings.forEach { planting ->
        addToSubstratumPopulations(
            planting.substratumId!!,
            planting.speciesId!!,
            planting.numPlants!!,
        )
      }
    }
  }

  fun undoDelivery(deliveryId: DeliveryId, undoWithdrawalId: WithdrawalId): DeliveryId {
    requirePermissions { updateDelivery(deliveryId) }

    val userId = currentUser().userId
    val now = clock.instant()

    val originalDelivery = fetchOneById(deliveryId)

    if (getWithdrawalPurpose(originalDelivery.withdrawalId) == WithdrawalPurpose.Undo) {
      throw UndoOfUndoNotAllowedException(originalDelivery.withdrawalId)
    }

    if (getWithdrawalPurpose(undoWithdrawalId) != WithdrawalPurpose.Undo) {
      throw WithdrawalNotUndoException(undoWithdrawalId)
    }

    // If the last observation of the planting site happened after the delivery, we don't want
    // to update the site's "plants since last observation" totals.
    val lastObservationTime = getLastObservationTime(originalDelivery.plantingSiteId)
    val deliveryNewerThanLastObservation =
        lastObservationTime == null || lastObservationTime < originalDelivery.createdTime

    return dslContext.transactionResult { _ ->
      val deliveriesRow =
          DeliveriesRow(
              createdBy = userId,
              createdTime = now,
              modifiedBy = userId,
              modifiedTime = now,
              plantingSiteId = originalDelivery.plantingSiteId,
              withdrawalId = undoWithdrawalId,
          )

      deliveriesDao.insert(deliveriesRow)
      val undoDeliveryId = deliveriesRow.id!!

      originalDelivery.plantings.forEach { originalPlanting ->
        val plantingType =
            when (originalPlanting.type) {
              PlantingType.Delivery -> PlantingType.Undo
              PlantingType.ReassignmentFrom -> PlantingType.ReassignmentTo
              PlantingType.ReassignmentTo -> PlantingType.ReassignmentFrom
              PlantingType.Undo ->
                  throw UndoOfUndoNotAllowedException(originalDelivery.withdrawalId)
            }

        val newPlantingsRow =
            PlantingsRow(
                createdBy = userId,
                createdTime = now,
                deliveryId = undoDeliveryId,
                numPlants = -originalPlanting.numPlants,
                plantingSiteId = originalDelivery.plantingSiteId,
                substratumId = originalPlanting.substratumId,
                plantingTypeId = plantingType,
                speciesId = originalPlanting.speciesId,
            )

        plantingsDao.insert(newPlantingsRow)

        addToPopulations(
            originalDelivery.plantingSiteId,
            originalPlanting.substratumId,
            originalPlanting.speciesId,
            -originalPlanting.numPlants,
            if (deliveryNewerThanLastObservation) -originalPlanting.numPlants else 0,
        )
      }

      undoDeliveryId
    }
  }

  /**
   * Recalculates the populations of all the species in a site's strata based on the populations of
   * its substrata. This is called in cases when the stratum-level totals become invalid, e.g.,
   * after a map edit that moves a substratum from one stratum to another.
   */
  fun recalculateStratumPopulations(plantingSiteId: PlantingSiteId) {
    dslContext.transaction { _ ->
      with(STRATUM_POPULATIONS) {
        dslContext
            .deleteFrom(STRATUM_POPULATIONS)
            .where(
                STRATUM_ID.`in`(
                    DSL.select(STRATA.ID)
                        .from(STRATA)
                        .where(STRATA.PLANTING_SITE_ID.eq(plantingSiteId))
                )
            )
            .execute()

        dslContext
            .insertInto(
                STRATUM_POPULATIONS,
                STRATUM_ID,
                SPECIES_ID,
                TOTAL_PLANTS,
                PLANTS_SINCE_LAST_OBSERVATION,
            )
            .select(
                with(SUBSTRATUM_POPULATIONS) {
                  DSL.select(
                          SUBSTRATA.STRATUM_ID,
                          SPECIES_ID,
                          DSL.sum(TOTAL_PLANTS).cast(SQLDataType.INTEGER),
                          DSL.sum(PLANTS_SINCE_LAST_OBSERVATION).cast(SQLDataType.INTEGER),
                      )
                      .from(SUBSTRATUM_POPULATIONS)
                      .join(SUBSTRATA)
                      .on(SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                      .where(SUBSTRATA.PLANTING_SITE_ID.eq(plantingSiteId))
                      .groupBy(SUBSTRATA.STRATUM_ID, SPECIES_ID)
                }
            )
            .execute()
      }
    }
  }

  /**
   * Recalculates the populations of all the species in a site based on the populations of its
   * strata. This is called in cases when the site-level totals become invalid, e.g., after we've
   * recalculated the substratum-level totals.
   */
  private fun recalculateSitePopulations(plantingSiteId: PlantingSiteId) {
    dslContext.transaction { _ ->
      with(PLANTING_SITE_POPULATIONS) {
        dslContext
            .deleteFrom(PLANTING_SITE_POPULATIONS)
            .where(PLANTING_SITE_ID.eq(plantingSiteId))
            .execute()

        dslContext
            .insertInto(
                PLANTING_SITE_POPULATIONS,
                PLANTING_SITE_ID,
                SPECIES_ID,
                TOTAL_PLANTS,
                PLANTS_SINCE_LAST_OBSERVATION,
            )
            .select(
                with(STRATUM_POPULATIONS) {
                  DSL.select(
                          DSL.value(plantingSiteId, PLANTING_SITE_ID),
                          SPECIES_ID,
                          DSL.sum(TOTAL_PLANTS).cast(SQLDataType.INTEGER),
                          DSL.sum(PLANTS_SINCE_LAST_OBSERVATION).cast(SQLDataType.INTEGER),
                      )
                      .from(STRATUM_POPULATIONS)
                      .join(STRATA)
                      .on(STRATUM_ID.eq(STRATA.ID))
                      .where(STRATA.PLANTING_SITE_ID.eq(plantingSiteId))
                      .groupBy(SPECIES_ID)
                }
            )
            .execute()
      }
    }
  }

  /**
   * Recalculates the substratum, stratum, and site populations for a planting site to match the
   * plant totals from plantings. This is used to correct discrepancies between the withdrawal log
   * and the population data, e.g., because batches were deleted.
   */
  fun recalculatePopulationsFromPlantings(plantingSiteId: PlantingSiteId) {
    if (
        !dslContext.fetchExists(
            STRATA,
            STRATA.PLANTING_SITE_ID.eq(plantingSiteId),
        )
    ) {
      throw IllegalArgumentException("Recalculation not supported for simple planting sites")
    }

    dslContext.transaction { _ ->
      // Remove any populations that no longer have any plantings, or whose plantings are canceled
      // out by reassignments to other substrata.
      dslContext
          .deleteFrom(SUBSTRATUM_POPULATIONS)
          .where(SUBSTRATUM_POPULATIONS.substrata.PLANTING_SITE_ID.eq(plantingSiteId))
          .and(
              SUBSTRATUM_POPULATIONS.SPECIES_ID.notIn(
                  DSL.select(PLANTINGS.SPECIES_ID)
                      .from(PLANTINGS)
                      .where(PLANTINGS.SUBSTRATUM_ID.eq(SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID))
                      .groupBy(PLANTINGS.SPECIES_ID)
                      .having(DSL.sum(PLANTINGS.NUM_PLANTS).gt(BigDecimal.ZERO))
              )
          )
          .execute()

      val sumField = DSL.sum(PLANTINGS.NUM_PLANTS).cast(SQLDataType.INTEGER).`as`("total_plants")

      dslContext
          .insertInto(
              SUBSTRATUM_POPULATIONS,
              SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
              SUBSTRATUM_POPULATIONS.SPECIES_ID,
              SUBSTRATUM_POPULATIONS.TOTAL_PLANTS,
              SUBSTRATUM_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION,
          )
          .select(
              DSL.select(
                      PLANTINGS.SUBSTRATUM_ID,
                      PLANTINGS.SPECIES_ID,
                      sumField,
                      DSL.value(0),
                  )
                  .from(PLANTINGS)
                  .join(SUBSTRATA)
                  .on(PLANTINGS.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                  .where(SUBSTRATA.PLANTING_SITE_ID.eq(plantingSiteId))
                  .groupBy(PLANTINGS.SUBSTRATUM_ID, PLANTINGS.SPECIES_ID)
          )
          .onConflict(
              SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
              SUBSTRATUM_POPULATIONS.SPECIES_ID,
          )
          .doUpdate()
          .set(SUBSTRATUM_POPULATIONS.TOTAL_PLANTS, DSL.excluded(sumField))
          .execute()

      recalculateStratumPopulations(plantingSiteId)
      recalculateSitePopulations(plantingSiteId)
    }
  }

  private fun addToPopulations(
      plantingSiteId: PlantingSiteId,
      substratumId: SubstratumId?,
      speciesId: SpeciesId,
      numPlants: Int,
      plantsSinceLastObservation: Int = numPlants,
  ) {
    with(PLANTING_SITE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_SITE_POPULATIONS)
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(SPECIES_ID, speciesId)
          .set(TOTAL_PLANTS, numPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, plantsSinceLastObservation)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(numPlants))
          .set(
              PLANTS_SINCE_LAST_OBSERVATION,
              PLANTS_SINCE_LAST_OBSERVATION.plus(plantsSinceLastObservation),
          )
          .execute()

      if (numPlants < 0) {
        dslContext
            .deleteFrom(PLANTING_SITE_POPULATIONS)
            .where(PLANTING_SITE_ID.eq(PLANTING_SITE_ID))
            .and(SPECIES_ID.eq(speciesId))
            .and(TOTAL_PLANTS.le(0))
            .execute()
      }
    }

    if (substratumId != null) {
      addToSubstratumPopulations(substratumId, speciesId, numPlants)
    }
  }

  private fun addToSubstratumPopulations(
      substratumId: SubstratumId,
      speciesId: SpeciesId,
      numPlants: Int,
      plantsSinceLastObservation: Int = numPlants,
  ) {
    val stratumId =
        dslContext
            .select(SUBSTRATA.STRATUM_ID)
            .from(SUBSTRATA)
            .where(SUBSTRATA.ID.eq(substratumId))
            .fetchOne(SUBSTRATA.STRATUM_ID) ?: throw SubstratumNotFoundException(substratumId)

    with(SUBSTRATUM_POPULATIONS) {
      dslContext
          .insertInto(SUBSTRATUM_POPULATIONS)
          .set(SUBSTRATUM_ID, substratumId)
          .set(SPECIES_ID, speciesId)
          .set(TOTAL_PLANTS, numPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, plantsSinceLastObservation)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(numPlants))
          .set(
              PLANTS_SINCE_LAST_OBSERVATION,
              PLANTS_SINCE_LAST_OBSERVATION.plus(plantsSinceLastObservation),
          )
          .execute()

      if (numPlants < 0) {
        dslContext
            .deleteFrom(SUBSTRATUM_POPULATIONS)
            .where(SUBSTRATUM_ID.eq(substratumId))
            .and(SPECIES_ID.eq(speciesId))
            .and(TOTAL_PLANTS.le(0))
            .execute()
      }
    }

    with(STRATUM_POPULATIONS) {
      dslContext
          .insertInto(STRATUM_POPULATIONS)
          .set(STRATUM_ID, stratumId)
          .set(SPECIES_ID, speciesId)
          .set(TOTAL_PLANTS, numPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, plantsSinceLastObservation)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(numPlants))
          .set(
              PLANTS_SINCE_LAST_OBSERVATION,
              PLANTS_SINCE_LAST_OBSERVATION.plus(plantsSinceLastObservation),
          )
          .execute()

      if (numPlants < 0) {
        dslContext
            .deleteFrom(STRATUM_POPULATIONS)
            .where(STRATUM_ID.eq(stratumId))
            .and(SPECIES_ID.eq(speciesId))
            .and(TOTAL_PLANTS.le(0))
            .execute()
      }
    }
  }

  private fun getPlantingSiteId(deliveryId: DeliveryId): PlantingSiteId {
    return with(DELIVERIES) {
      dslContext
          .select(PLANTING_SITE_ID)
          .from(DELIVERIES)
          .where(ID.eq(deliveryId))
          .fetchOne(PLANTING_SITE_ID) ?: throw DeliveryNotFoundException(deliveryId)
    }
  }

  private fun plantingSiteHasSubstrata(plantingSiteId: PlantingSiteId): Boolean {
    return dslContext.fetchExists(
        DSL.selectOne().from(SUBSTRATA).where(SUBSTRATA.PLANTING_SITE_ID.eq(plantingSiteId))
    )
  }

  private fun deliveryAlreadyUndone(deliveryId: DeliveryId): Boolean {
    return dslContext.fetchExists(
        DSL.selectOne()
            .from(WITHDRAWALS)
            .join(DELIVERIES)
            .on(WITHDRAWALS.UNDOES_WITHDRAWAL_ID.eq(DELIVERIES.WITHDRAWAL_ID))
            .where(DELIVERIES.ID.eq(deliveryId))
    )
  }

  private fun getWithdrawalPurpose(deliveryId: DeliveryId): WithdrawalPurpose? {
    return dslContext
        .select(WITHDRAWALS.PURPOSE_ID)
        .from(WITHDRAWALS)
        .join(DELIVERIES)
        .on(WITHDRAWALS.ID.eq(DELIVERIES.WITHDRAWAL_ID))
        .where(DELIVERIES.ID.eq(deliveryId))
        .fetchOne(WITHDRAWALS.PURPOSE_ID)
  }

  private fun getWithdrawalPurpose(withdrawalId: WithdrawalId): WithdrawalPurpose {
    return dslContext
        .select(WITHDRAWALS.PURPOSE_ID)
        .from(WITHDRAWALS)
        .where(WITHDRAWALS.ID.eq(withdrawalId))
        .fetchOne(WITHDRAWALS.PURPOSE_ID) ?: throw WithdrawalNotFoundException(withdrawalId)
  }

  private fun getLastObservationTime(plantingSiteId: PlantingSiteId): Instant? {
    return dslContext
        .select(DSL.max(OBSERVATIONS.COMPLETED_TIME))
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .fetchOne(DSL.max(OBSERVATIONS.COMPLETED_TIME))
  }

  data class Reassignment(
      val fromPlantingId: PlantingId,
      val numPlants: Int,
      val notes: String? = null,
      val toSubstratumId: SubstratumId,
  ) {
    init {
      if (numPlants <= 0) {
        throw IllegalArgumentException("Number of plants must be 1 or more for reassignments")
      }
    }
  }
}
