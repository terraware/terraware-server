package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.model.DeliveryModel
import com.terraformation.backend.tracking.model.PlantingModel
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.InstantSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class DeliveryStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(DELIVERIES, PLANTINGS)

  private val clock: InstantSource = mockk()
  private val store: DeliveryStore by lazy {
    DeliveryStore(clock, deliveriesDao, dslContext, ParentStore(dslContext), plantingsDao)
  }

  private val plantingSiteId by lazy { insertPlantingSite() }
  private val plantingZoneId by lazy { insertPlantingZone(plantingSiteId = plantingSiteId) }
  private val plotId by lazy { insertPlot(plantingZoneId = plantingZoneId) }
  private val speciesId1 by lazy { insertSpecies(1) }
  private val speciesId2 by lazy { insertSpecies(2) }
  private val withdrawalId by lazy { insertWithdrawal() }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreateDelivery(any()) } returns true
    every { user.canReadDelivery(any()) } returns true
    every { user.canReadPlanting(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canUpdateDelivery(any()) } returns true

    insertUser()
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
  }

  @Nested
  inner class CreateDelivery {
    @Test
    fun `creates delivery with multiple plantings`() {
      val deliveryId =
          store.createDelivery(
              withdrawalId, plantingSiteId, plotId, mapOf(speciesId1 to 15, speciesId2 to 20))

      assertEquals(
          listOf(
              DeliveriesRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  id = deliveryId,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  plantingSiteId = plantingSiteId,
                  withdrawalId = withdrawalId,
              ),
          ),
          deliveriesDao.findAll(),
          "Deliveries")

      assertEquals(
          setOf(
              PlantingsRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  deliveryId = deliveryId,
                  numPlants = 15,
                  plantingSiteId = plantingSiteId,
                  plantingTypeId = PlantingType.Delivery,
                  plotId = plotId,
                  speciesId = speciesId1,
              ),
              PlantingsRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  deliveryId = deliveryId,
                  numPlants = 20,
                  plantingSiteId = plantingSiteId,
                  plantingTypeId = PlantingType.Delivery,
                  plotId = plotId,
                  speciesId = speciesId2,
              ),
          ),
          plantingsDao.findAll().map { it.copy(id = null) }.toSet(),
          "Plantings")
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canCreateDelivery(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.createDelivery(withdrawalId, plantingSiteId, null, emptyMap())
      }
    }

    @Test
    fun `requires plot ID if planting site has plots`() {
      // Cause the plot to be inserted by lazy evaluation
      assertNotNull(plotId)

      assertThrows<DeliveryMissingPlotException> {
        store.createDelivery(withdrawalId, plantingSiteId, null, mapOf(speciesId1 to 5))
      }
    }

    @Test
    fun `requires that planting site be owned by same organization as withdrawal`() {
      val otherOrgId = OrganizationId(2)
      insertOrganization(otherOrgId)
      plantingSitesDao.update(
          plantingSitesDao.fetchOneById(plantingSiteId)!!.copy(organizationId = otherOrgId))

      assertThrows<CrossOrganizationDeliveryNotAllowedException> {
        store.createDelivery(withdrawalId, plantingSiteId, null, emptyMap())
      }
    }
  }

  @Nested
  inner class ReassignDelivery {
    private val deliveryId: DeliveryId by lazy {
      store.createDelivery(
          withdrawalId, plantingSiteId, plotId, mapOf(speciesId1 to 100, speciesId2 to 100))
    }
    private val species1PlantingId: PlantingId by lazy {
      plantingsDao.fetchByDeliveryId(deliveryId).first { it.speciesId == speciesId1 }.id!!
    }
    private val species2PlantingId: PlantingId by lazy {
      plantingsDao.fetchByDeliveryId(deliveryId).first { it.speciesId == speciesId2 }.id!!
    }
    private val otherPlotId: PlotId by lazy { insertPlot(plantingZoneId = plantingZoneId) }

    @Test
    fun `creates reassignment plantings`() {
      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = species1PlantingId,
                  numPlants = 1,
                  notes = "notes 1",
                  toPlotId = otherPlotId,
              ),
              DeliveryStore.Reassignment(
                  fromPlantingId = species2PlantingId,
                  numPlants = 2,
                  notes = "notes 2",
                  toPlotId = otherPlotId,
              ),
          ))

      val commonValues =
          PlantingsRow(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              deliveryId = deliveryId,
              plantingSiteId = plantingSiteId,
          )

      val expected =
          setOf(
              commonValues.copy(
                  plantingTypeId = PlantingType.ReassignmentFrom,
                  plotId = plotId,
                  speciesId = speciesId1,
                  numPlants = -1,
              ),
              commonValues.copy(
                  notes = "notes 1",
                  plantingTypeId = PlantingType.ReassignmentTo,
                  plotId = otherPlotId,
                  speciesId = speciesId1,
                  numPlants = 1,
              ),
              commonValues.copy(
                  plantingTypeId = PlantingType.ReassignmentFrom,
                  plotId = plotId,
                  speciesId = speciesId2,
                  numPlants = -2,
              ),
              commonValues.copy(
                  notes = "notes 2",
                  plantingTypeId = PlantingType.ReassignmentTo,
                  plotId = otherPlotId,
                  speciesId = speciesId2,
                  numPlants = 2,
              ),
          )

      val actual =
          plantingsDao
              .findAll()
              .filter { it.plantingTypeId != PlantingType.Delivery }
              .map { it.copy(id = null) }
              .toSet()

      assertEquals(expected, actual, "Reassignment plantings")

      val deliveriesRow = deliveriesDao.fetchOneById(deliveryId)!!
      assertEquals(user.userId, deliveriesRow.reassignedBy, "Reassigned user ID on delivery")
      assertEquals(clock.instant(), deliveriesRow.reassignedTime, "Reassigned time on delivery")
    }

    @Test
    fun `throws exception if plantings are from a different delivery`() {
      val otherWithdrawalId = insertWithdrawal()
      val otherDeliveryId =
          store.createDelivery(otherWithdrawalId, plantingSiteId, plotId, mapOf(speciesId1 to 10))
      val otherDeliveryPlantingId = plantingsDao.fetchByDeliveryId(otherDeliveryId).first().id!!

      assertThrows<CrossDeliveryReassignmentNotAllowedException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = otherDeliveryPlantingId,
                    numPlants = 1,
                    toPlotId = otherPlotId)))
      }

      every { user.canReadPlanting(otherDeliveryPlantingId) } returns false

      assertThrows<PlantingNotFoundException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = otherDeliveryPlantingId,
                    numPlants = 1,
                    toPlotId = otherPlotId)))
      }
    }

    @Test
    fun `throws exception if trying to reassign from a plot to itself`() {
      assertThrows<ReassignmentToSamePlotNotAllowedException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = species1PlantingId, numPlants = 1, toPlotId = plotId)))
      }
    }

    @Test
    fun `throws exception if trying to reassign more plants than were delivered`() {
      assertThrows<ReassignmentTooLargeException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = species1PlantingId,
                    numPlants = 10000,
                    toPlotId = otherPlotId)))
      }
    }

    @Test
    fun `throws exception if reassigning from a planting that already has a reassignment for the species`() {
      val reassignment =
          DeliveryStore.Reassignment(
              fromPlantingId = species1PlantingId, numPlants = 1, toPlotId = otherPlotId)
      store.reassignDelivery(deliveryId, listOf(reassignment))

      assertThrows<ReassignmentExistsException> {
        store.reassignDelivery(deliveryId, listOf(reassignment))
      }
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canUpdateDelivery(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = species1PlantingId,
                    numPlants = 1,
                    notes = "notes 1",
                    toPlotId = otherPlotId,
                ),
            ))
      }
    }
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns delivery and plantings`() {
      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      val plantingId1 =
          insertPlanting(deliveryId = deliveryId, speciesId = speciesId1, plotId = plotId)
      val plantingId2 =
          insertPlanting(
              deliveryId = deliveryId, speciesId = speciesId2, plotId = plotId, numPlants = 2)

      val expected =
          DeliveryModel(
              id = deliveryId,
              plantings =
                  listOf(
                      PlantingModel(
                          id = plantingId1,
                          numPlants = 1,
                          speciesId = speciesId1,
                          plotId = plotId,
                          type = PlantingType.Delivery),
                      PlantingModel(
                          id = plantingId2,
                          numPlants = 2,
                          speciesId = speciesId2,
                          plotId = plotId,
                          type = PlantingType.Delivery),
                  ),
              plantingSiteId = plantingSiteId,
              withdrawalId = withdrawalId,
          )

      assertEquals(expected, store.fetchOneById(deliveryId), "fetchOneById")
      assertEquals(expected, store.fetchOneByWithdrawalId(withdrawalId), "fetchOneByWithdrawalId")
    }

    @Test
    fun `fetchOneById throws exception if no permission`() {
      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)

      every { user.canReadDelivery(any()) } returns false

      assertThrows<DeliveryNotFoundException> { store.fetchOneById(deliveryId) }
    }

    @Test
    fun `fetchOneByWithdrawalId returns null if no permission`() {
      insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)

      every { user.canReadDelivery(any()) } returns false

      assertNull(store.fetchOneByWithdrawalId(withdrawalId))
    }
  }
}
