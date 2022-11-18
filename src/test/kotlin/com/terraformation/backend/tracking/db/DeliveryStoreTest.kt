package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.InstantSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
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
  private val plotId by lazy {
    insertPlot(plantingSiteId = plantingSiteId, plantingZoneId = plantingZoneId)
  }
  private val speciesId1 by lazy { insertSpecies(1) }
  private val speciesId2 by lazy { insertSpecies(2) }
  private val withdrawalId by lazy { insertWithdrawal() }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreateDelivery(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true

    insertUser()
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
  }

  @Test
  fun `createDelivery creates delivery with multiple plantings`() {
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
  fun `createDelivery throws exception if no permission`() {
    every { user.canCreateDelivery(any()) } returns false

    assertThrows<AccessDeniedException> {
      store.createDelivery(withdrawalId, plantingSiteId, null, emptyMap())
    }
  }

  @Test
  fun `createDelivery requires plot ID if planting site has plots`() {
    // Cause the plot to be inserted by lazy evaluation
    assertNotNull(plotId)

    assertThrows<DeliveryMissingPlotException> {
      store.createDelivery(withdrawalId, plantingSiteId, null, mapOf(speciesId1 to 5))
    }
  }

  @Test
  fun `createDelivery requires that planting site be owned by same organization as withdrawal`() {
    val otherOrgId = OrganizationId(2)
    insertOrganization(otherOrgId)
    plantingSitesDao.update(
        plantingSitesDao.fetchOneById(plantingSiteId)!!.copy(organizationId = otherOrgId))

    assertThrows<CrossOrganizationDeliveryNotAllowedException> {
      store.createDelivery(withdrawalId, plantingSiteId, null, emptyMap())
    }
  }
}
