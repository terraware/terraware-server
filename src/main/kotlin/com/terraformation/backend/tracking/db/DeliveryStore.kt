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
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.daos.DeliveriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingsDao
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
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
      plantingSubzoneId: PlantingSubzoneId? = null,
      quantities: Map<SpeciesId, Int>,
  ): DeliveryId {
    requirePermissions { createDelivery(plantingSiteId) }

    val now = clock.instant()
    val userId = currentUser().userId

    if (plantingSubzoneId == null && plantingSiteHasSubzones(plantingSiteId)) {
      throw DeliveryMissingSubzoneException(plantingSiteId)
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
                    plantingSubzoneId = plantingSubzoneId,
                    speciesId = speciesId,
                )

            plantingsDao.insert(plantingsRow)

            addToPopulations(plantingSiteId, plantingSubzoneId, speciesId, numPlants)

            plantingsRow.id!!
          }

      log.info(
          "Created delivery $deliveryId to planting site $plantingSiteId plot $plantingSubzoneId with " +
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

          if (reassignment.toPlantingSubzoneId == originalPlanting.plantingSubzoneId) {
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
                  plantingSubzoneId = originalPlanting.plantingSubzoneId,
              ),
              skeletonRow.copy(
                  notes = reassignment.notes,
                  numPlants = reassignment.numPlants,
                  plantingTypeId = PlantingType.ReassignmentTo,
                  plantingSubzoneId = reassignment.toPlantingSubzoneId,
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
        addToSubzonePopulations(
            planting.plantingSubzoneId!!,
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
                plantingSubzoneId = originalPlanting.plantingSubzoneId,
                plantingTypeId = plantingType,
                speciesId = originalPlanting.speciesId,
            )

        plantingsDao.insert(newPlantingsRow)

        addToPopulations(
            originalDelivery.plantingSiteId,
            originalPlanting.plantingSubzoneId,
            originalPlanting.speciesId,
            -originalPlanting.numPlants,
            if (deliveryNewerThanLastObservation) -originalPlanting.numPlants else 0,
        )
      }

      undoDeliveryId
    }
  }

  /**
   * Recalculates the populations of all the species in a site's planting zones based on the
   * populations of its subzones. This is called in cases when the zone-level totals become invalid,
   * e.g., after a map edit that moves a subzone from one zone to another.
   */
  fun recalculateZonePopulations(plantingSiteId: PlantingSiteId) {
    dslContext.transaction { _ ->
      with(PLANTING_ZONE_POPULATIONS) {
        dslContext
            .deleteFrom(PLANTING_ZONE_POPULATIONS)
            .where(
                PLANTING_ZONE_ID.`in`(
                    DSL.select(PLANTING_ZONES.ID)
                        .from(PLANTING_ZONES)
                        .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))
                )
            )
            .execute()

        dslContext
            .insertInto(
                PLANTING_ZONE_POPULATIONS,
                PLANTING_ZONE_ID,
                SPECIES_ID,
                TOTAL_PLANTS,
                PLANTS_SINCE_LAST_OBSERVATION,
            )
            .select(
                with(PLANTING_SUBZONE_POPULATIONS) {
                  DSL.select(
                          PLANTING_SUBZONES.PLANTING_ZONE_ID,
                          SPECIES_ID,
                          DSL.sum(TOTAL_PLANTS).cast(SQLDataType.INTEGER),
                          DSL.sum(PLANTS_SINCE_LAST_OBSERVATION).cast(SQLDataType.INTEGER),
                      )
                      .from(PLANTING_SUBZONE_POPULATIONS)
                      .join(PLANTING_SUBZONES)
                      .on(PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                      .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
                      .groupBy(PLANTING_SUBZONES.PLANTING_ZONE_ID, SPECIES_ID)
                }
            )
            .execute()
      }
    }
  }

  /**
   * Recalculates the populations of all the species in a site based on the populations of its
   * planting zones. This is called in cases when the site-level totals become invalid, e.g., after
   * we've recalculated the subzone-level totals.
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
                with(PLANTING_ZONE_POPULATIONS) {
                  DSL.select(
                          DSL.value(plantingSiteId, PLANTING_SITE_ID),
                          SPECIES_ID,
                          DSL.sum(TOTAL_PLANTS).cast(SQLDataType.INTEGER),
                          DSL.sum(PLANTS_SINCE_LAST_OBSERVATION).cast(SQLDataType.INTEGER),
                      )
                      .from(PLANTING_ZONE_POPULATIONS)
                      .join(PLANTING_ZONES)
                      .on(PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
                      .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))
                      .groupBy(SPECIES_ID)
                }
            )
            .execute()
      }
    }
  }

  /**
   * Recalculates the subzone, zone, and site populations for a planting site to match the plant
   * totals from plantings. This is used to correct discrepancies between the withdrawal log and the
   * population data, e.g., because batches were deleted.
   */
  fun recalculatePopulationsFromPlantings(plantingSiteId: PlantingSiteId) {
    if (
        !dslContext.fetchExists(PLANTING_ZONES, PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))
    ) {
      throw IllegalArgumentException("Recalculation not supported for simple planting sites")
    }

    dslContext.transaction { _ ->
      // Remove any populations that no longer have any plantings, or whose plantings are canceled
      // out by reassignments to other subzones.
      dslContext
          .deleteFrom(PLANTING_SUBZONE_POPULATIONS)
          .where(PLANTING_SUBZONE_POPULATIONS.plantingSubzones.PLANTING_SITE_ID.eq(plantingSiteId))
          .and(
              PLANTING_SUBZONE_POPULATIONS.SPECIES_ID.notIn(
                  DSL.select(PLANTINGS.SPECIES_ID)
                      .from(PLANTINGS)
                      .where(
                          PLANTINGS.PLANTING_SUBZONE_ID.eq(
                              PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID
                          )
                      )
                      .groupBy(PLANTINGS.SPECIES_ID)
                      .having(DSL.sum(PLANTINGS.NUM_PLANTS).gt(BigDecimal.ZERO))
              )
          )
          .execute()

      val sumField = DSL.sum(PLANTINGS.NUM_PLANTS).cast(SQLDataType.INTEGER).`as`("total_plants")

      dslContext
          .insertInto(
              PLANTING_SUBZONE_POPULATIONS,
              PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID,
              PLANTING_SUBZONE_POPULATIONS.SPECIES_ID,
              PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS,
              PLANTING_SUBZONE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION,
          )
          .select(
              DSL.select(
                      PLANTINGS.PLANTING_SUBZONE_ID,
                      PLANTINGS.SPECIES_ID,
                      sumField,
                      DSL.value(0),
                  )
                  .from(PLANTINGS)
                  .join(PLANTING_SUBZONES)
                  .on(PLANTINGS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                  .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
                  .groupBy(PLANTINGS.PLANTING_SUBZONE_ID, PLANTINGS.SPECIES_ID)
          )
          .onConflict(
              PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID,
              PLANTING_SUBZONE_POPULATIONS.SPECIES_ID,
          )
          .doUpdate()
          .set(PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS, DSL.excluded(sumField))
          .execute()

      recalculateZonePopulations(plantingSiteId)
      recalculateSitePopulations(plantingSiteId)
    }
  }

  private fun addToPopulations(
      plantingSiteId: PlantingSiteId,
      plantingSubzoneId: PlantingSubzoneId?,
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

    if (plantingSubzoneId != null) {
      addToSubzonePopulations(plantingSubzoneId, speciesId, numPlants)
    }
  }

  private fun addToSubzonePopulations(
      plantingSubzoneId: PlantingSubzoneId,
      speciesId: SpeciesId,
      numPlants: Int,
      plantsSinceLastObservation: Int = numPlants,
  ) {
    val plantingZoneId =
        dslContext
            .select(PLANTING_SUBZONES.PLANTING_ZONE_ID)
            .from(PLANTING_SUBZONES)
            .where(PLANTING_SUBZONES.ID.eq(plantingSubzoneId))
            .fetchOne(PLANTING_SUBZONES.PLANTING_ZONE_ID)
            ?: throw PlantingSubzoneNotFoundException(plantingSubzoneId)

    with(PLANTING_SUBZONE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_SUBZONE_POPULATIONS)
          .set(PLANTING_SUBZONE_ID, plantingSubzoneId)
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
            .deleteFrom(PLANTING_SUBZONE_POPULATIONS)
            .where(PLANTING_SUBZONE_ID.eq(plantingSubzoneId))
            .and(SPECIES_ID.eq(speciesId))
            .and(TOTAL_PLANTS.le(0))
            .execute()
      }
    }

    with(PLANTING_ZONE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_ZONE_POPULATIONS)
          .set(PLANTING_ZONE_ID, plantingZoneId)
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
            .deleteFrom(PLANTING_ZONE_POPULATIONS)
            .where(PLANTING_ZONE_ID.eq(plantingZoneId))
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

  private fun plantingSiteHasSubzones(plantingSiteId: PlantingSiteId): Boolean {
    return dslContext.fetchExists(
        DSL.selectOne()
            .from(PLANTING_SUBZONES)
            .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
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
      val toPlantingSubzoneId: PlantingSubzoneId,
  ) {
    init {
      if (numPlants <= 0) {
        throw IllegalArgumentException("Number of plants must be 1 or more for reassignments")
      }
    }
  }
}
