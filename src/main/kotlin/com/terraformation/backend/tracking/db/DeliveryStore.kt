package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
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
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.db.WithdrawalNotFoundException
import com.terraformation.backend.tracking.model.DeliveryModel
import com.terraformation.backend.tracking.model.PlantingModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL

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
                  .orderBy(PLANTINGS.ID))
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
              "plantings $plantingIds")

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

    val newPlantings =
        reassignments.flatMap { reassignment ->
          val fromPlantingId = reassignment.fromPlantingId

          requirePermissions { readPlanting(fromPlantingId) }

          val originalPlanting =
              originalPlantings[fromPlantingId] ?: throw PlantingNotFoundException(fromPlantingId)
          val speciesId = originalPlanting.speciesId!!

          if (originalPlanting.deliveryId != deliveryId) {
            throw CrossDeliveryReassignmentNotAllowedException(
                fromPlantingId, originalPlanting.deliveryId!!, deliveryId)
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
            planting.plantingSubzoneId!!, planting.speciesId!!, planting.numPlants!!)
      }
    }
  }

  private fun addToPopulations(
      plantingSiteId: PlantingSiteId,
      plantingSubzoneId: PlantingSubzoneId?,
      speciesId: SpeciesId,
      numPlants: Int
  ) {
    with(PLANTING_SITE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_SITE_POPULATIONS)
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(SPECIES_ID, speciesId)
          .set(TOTAL_PLANTS, numPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, numPlants)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(numPlants))
          .set(PLANTS_SINCE_LAST_OBSERVATION, PLANTS_SINCE_LAST_OBSERVATION.plus(numPlants))
          .execute()
    }

    if (plantingSubzoneId != null) {
      addToSubzonePopulations(plantingSubzoneId, speciesId, numPlants)
    }
  }

  private fun addToSubzonePopulations(
      plantingSubzoneId: PlantingSubzoneId,
      speciesId: SpeciesId,
      numPlants: Int
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
          .set(PLANTS_SINCE_LAST_OBSERVATION, numPlants)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(numPlants))
          .set(PLANTS_SINCE_LAST_OBSERVATION, PLANTS_SINCE_LAST_OBSERVATION.plus(numPlants))
          .execute()
    }

    with(PLANTING_ZONE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_ZONE_POPULATIONS)
          .set(PLANTING_ZONE_ID, plantingZoneId)
          .set(SPECIES_ID, speciesId)
          .set(TOTAL_PLANTS, numPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, numPlants)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(numPlants))
          .set(PLANTS_SINCE_LAST_OBSERVATION, PLANTS_SINCE_LAST_OBSERVATION.plus(numPlants))
          .execute()
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
            .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId)))
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
