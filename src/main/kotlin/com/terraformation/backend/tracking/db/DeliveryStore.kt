package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.db.tracking.tables.daos.DeliveriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingsDao
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.db.WithdrawalNotFoundException
import java.time.InstantSource
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class DeliveryStore(
    private val clock: InstantSource,
    private val deliveriesDao: DeliveriesDao,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
    private val plantingsDao: PlantingsDao,
) {
  private val log = perClassLogger()

  fun createDelivery(
      withdrawalId: WithdrawalId,
      plantingSiteId: PlantingSiteId,
      plotId: PlotId? = null,
      quantities: Map<SpeciesId, Int>,
  ): DeliveryId {
    requirePermissions { createDelivery(plantingSiteId) }

    val now = clock.instant()
    val userId = currentUser().userId

    if (plotId == null && plantingSiteHasPlots(plantingSiteId)) {
      throw DeliveryMissingPlotException(plantingSiteId)
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
                    plotId = plotId,
                    speciesId = speciesId,
                )

            plantingsDao.insert(plantingsRow)
            plantingsRow.id!!
          }

      log.info(
          "Created delivery $deliveryId to planting site $plantingSiteId plot $plotId with " +
              "plantings $plantingIds")

      deliveryId
    }
  }

  private fun plantingSiteHasPlots(plantingSiteId: PlantingSiteId): Boolean {
    return dslContext.fetchExists(
        DSL.selectOne().from(PLOTS).where(PLOTS.PLANTING_SITE_ID.eq(plantingSiteId)))
  }
}
