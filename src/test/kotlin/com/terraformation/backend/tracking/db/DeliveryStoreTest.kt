package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumPopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstratumPopulationsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.UndoOfUndoNotAllowedException
import com.terraformation.backend.tracking.model.DeliveryModel
import com.terraformation.backend.tracking.model.PlantingModel
import io.mockk.every
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class DeliveryStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: DeliveryStore by lazy {
    DeliveryStore(clock, deliveriesDao, dslContext, ParentStore(dslContext), plantingsDao)
  }

  private val plantingSiteId by lazy { insertPlantingSite(x = 0) }
  private val plantingZoneId by lazy { insertPlantingZone(plantingSiteId = plantingSiteId) }
  private val plantingSubzoneId by lazy { insertPlantingSubzone(plantingZoneId = plantingZoneId) }
  private val speciesId1 by lazy { insertSpecies() }
  private val speciesId2 by lazy { insertSpecies() }
  private val withdrawalId by lazy { insertNurseryWithdrawal() }

  @BeforeEach
  fun setUp() {
    every { user.canCreateDelivery(any()) } returns true
    every { user.canReadDelivery(any()) } returns true
    every { user.canReadPlanting(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canUpdateDelivery(any()) } returns true

    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
  }

  @Nested
  inner class CreateDelivery {
    @Test
    fun `creates delivery with multiple plantings`() {
      insertPlantingSitePopulation(plantingSiteId, speciesId1, 6, 5)
      insertPlantingZonePopulation(plantingZoneId, speciesId1, 4, 3)
      insertPlantingSubzonePopulation(plantingSubzoneId, speciesId1, 2, 1)

      val deliveryId =
          store.createDelivery(
              withdrawalId,
              plantingSiteId,
              plantingSubzoneId,
              mapOf(speciesId1 to 15, speciesId2 to 20),
          )

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
          "Deliveries",
      )

      assertSetEquals(
          setOf(
              PlantingsRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  deliveryId = deliveryId,
                  numPlants = 15,
                  plantingSiteId = plantingSiteId,
                  plantingTypeId = PlantingType.Delivery,
                  substratumId = plantingSubzoneId,
                  speciesId = speciesId1,
              ),
              PlantingsRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  deliveryId = deliveryId,
                  numPlants = 20,
                  plantingSiteId = plantingSiteId,
                  plantingTypeId = PlantingType.Delivery,
                  substratumId = plantingSubzoneId,
                  speciesId = speciesId2,
              ),
          ),
          plantingsDao.findAll().map { it.copy(id = null) }.toSet(),
          "Plantings",
      )

      assertSetEquals(
          setOf(
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 21, 20),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 20, 20),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )

      assertSetEquals(
          setOf(
              StratumPopulationsRow(plantingZoneId, speciesId1, 19, 18),
              StratumPopulationsRow(plantingZoneId, speciesId2, 20, 20),
          ),
          plantingZonePopulationsDao.findAll().toSet(),
          "Planting zone populations",
      )

      assertSetEquals(
          setOf(
              SubstratumPopulationsRow(plantingSubzoneId, speciesId1, 17, 16),
              SubstratumPopulationsRow(plantingSubzoneId, speciesId2, 20, 20),
          ),
          plantingSubzonePopulationsDao.findAll().toSet(),
          "Planting subzone populations",
      )
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canCreateDelivery(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.createDelivery(withdrawalId, plantingSiteId, null, emptyMap())
      }
    }

    @Test
    fun `requires planting subzone ID if planting site has planting subzones`() {
      // Cause the planting subzone to be inserted by lazy evaluation
      assertNotNull(plantingSubzoneId)

      assertThrows<DeliveryMissingSubzoneException> {
        store.createDelivery(withdrawalId, plantingSiteId, null, mapOf(speciesId1 to 5))
      }
    }

    @Test
    fun `requires that planting site be owned by same organization as withdrawal`() {
      val otherOrgId = insertOrganization()
      plantingSitesDao.update(
          plantingSitesDao.fetchOneById(plantingSiteId)!!.copy(organizationId = otherOrgId)
      )

      assertThrows<CrossOrganizationDeliveryNotAllowedException> {
        store.createDelivery(withdrawalId, plantingSiteId, null, emptyMap())
      }
    }
  }

  @Nested
  inner class ReassignDelivery {
    private val deliveryId: DeliveryId by lazy {
      store.createDelivery(
          withdrawalId,
          plantingSiteId,
          plantingSubzoneId,
          mapOf(speciesId1 to 100, speciesId2 to 100),
      )
    }
    private val species1PlantingId: PlantingId by lazy {
      plantingsDao.fetchByDeliveryId(deliveryId).first { it.speciesId == speciesId1 }.id!!
    }
    private val species2PlantingId: PlantingId by lazy {
      plantingsDao.fetchByDeliveryId(deliveryId).first { it.speciesId == speciesId2 }.id!!
    }
    private val otherPlantingSubzoneId: SubstratumId by lazy {
      insertPlantingSubzone(plantingZoneId = plantingZoneId)
    }

    @Test
    fun `creates reassignment plantings`() {
      insertPlantingSitePopulation(plantingSiteId, speciesId1, 6, 5)
      insertPlantingZonePopulation(plantingZoneId, speciesId1, 4, 3)
      insertPlantingSubzonePopulation(plantingSubzoneId, speciesId1, 2, 1)

      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = species1PlantingId,
                  numPlants = 1,
                  notes = "notes 1",
                  toPlantingSubzoneId = otherPlantingSubzoneId,
              ),
              DeliveryStore.Reassignment(
                  fromPlantingId = species2PlantingId,
                  numPlants = 2,
                  notes = "notes 2",
                  toPlantingSubzoneId = otherPlantingSubzoneId,
              ),
          ),
      )

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
                  substratumId = plantingSubzoneId,
                  speciesId = speciesId1,
                  numPlants = -1,
              ),
              commonValues.copy(
                  notes = "notes 1",
                  plantingTypeId = PlantingType.ReassignmentTo,
                  substratumId = otherPlantingSubzoneId,
                  speciesId = speciesId1,
                  numPlants = 1,
              ),
              commonValues.copy(
                  plantingTypeId = PlantingType.ReassignmentFrom,
                  substratumId = plantingSubzoneId,
                  speciesId = speciesId2,
                  numPlants = -2,
              ),
              commonValues.copy(
                  notes = "notes 2",
                  plantingTypeId = PlantingType.ReassignmentTo,
                  substratumId = otherPlantingSubzoneId,
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

      assertSetEquals(
          setOf(
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 106, 105),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 100, 100),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )

      assertSetEquals(
          setOf(
              StratumPopulationsRow(plantingZoneId, speciesId1, 104, 103),
              StratumPopulationsRow(plantingZoneId, speciesId2, 100, 100),
          ),
          plantingZonePopulationsDao.findAll().toSet(),
          "Planting zone populations",
      )

      assertSetEquals(
          setOf(
              SubstratumPopulationsRow(plantingSubzoneId, speciesId1, 101, 100),
              SubstratumPopulationsRow(plantingSubzoneId, speciesId2, 98, 98),
              SubstratumPopulationsRow(otherPlantingSubzoneId, speciesId1, 1, 1),
              SubstratumPopulationsRow(otherPlantingSubzoneId, speciesId2, 2, 2),
          ),
          plantingSubzonePopulationsDao.findAll().toSet(),
          "Planting subzone populations",
      )
    }

    @Test
    fun `throws exception if plantings are from a different delivery`() {
      val otherWithdrawalId = insertNurseryWithdrawal()
      val otherDeliveryId =
          store.createDelivery(
              otherWithdrawalId,
              plantingSiteId,
              plantingSubzoneId,
              mapOf(speciesId1 to 10),
          )
      val otherDeliveryPlantingId = plantingsDao.fetchByDeliveryId(otherDeliveryId).first().id!!

      assertThrows<CrossDeliveryReassignmentNotAllowedException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = otherDeliveryPlantingId,
                    numPlants = 1,
                    toPlantingSubzoneId = otherPlantingSubzoneId,
                )
            ),
        )
      }

      every { user.canReadPlanting(otherDeliveryPlantingId) } returns false

      assertThrows<PlantingNotFoundException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = otherDeliveryPlantingId,
                    numPlants = 1,
                    toPlantingSubzoneId = otherPlantingSubzoneId,
                )
            ),
        )
      }
    }

    @Test
    fun `throws exception if trying to reassign from a plot to itself`() {
      assertThrows<ReassignmentToSamePlotNotAllowedException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = species1PlantingId,
                    numPlants = 1,
                    toPlantingSubzoneId = plantingSubzoneId,
                )
            ),
        )
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
                    toPlantingSubzoneId = otherPlantingSubzoneId,
                )
            ),
        )
      }
    }

    @Test
    fun `throws exception if reassigning from a planting that already has a reassignment for the species`() {
      val reassignment =
          DeliveryStore.Reassignment(
              fromPlantingId = species1PlantingId,
              numPlants = 1,
              toPlantingSubzoneId = otherPlantingSubzoneId,
          )
      store.reassignDelivery(deliveryId, listOf(reassignment))

      assertThrows<ReassignmentExistsException> {
        store.reassignDelivery(deliveryId, listOf(reassignment))
      }
    }

    @Test
    fun `throws exception if reassigning a delivery whose withdrawal has been undone`() {
      // Insert the original delivery and planting.
      assertNotNull(species1PlantingId)

      insertNurseryWithdrawal(purpose = WithdrawalPurpose.Undo, undoesWithdrawalId = withdrawalId)
      insertDelivery(plantingSiteId = plantingSiteId)
      insertPlanting(
          speciesId = speciesId1,
          numPlants = -1,
          plantingSiteId = plantingSiteId,
          plantingTypeId = PlantingType.Undo,
      )

      val reassignment =
          DeliveryStore.Reassignment(
              fromPlantingId = species1PlantingId,
              numPlants = 1,
              toPlantingSubzoneId = otherPlantingSubzoneId,
          )

      assertThrows<ReassignmentOfUndoneWithdrawalNotAllowedException> {
        store.reassignDelivery(deliveryId, listOf(reassignment))
      }
    }

    @Test
    fun `throws exception if reassigning an undo delivery`() {
      // Insert the original delivery and planting.
      assertNotNull(species1PlantingId)

      insertNurseryWithdrawal(purpose = WithdrawalPurpose.Undo, undoesWithdrawalId = withdrawalId)
      insertDelivery(plantingSiteId = plantingSiteId)
      insertPlanting(
          speciesId = speciesId1,
          numPlants = -1,
          plantingSiteId = plantingSiteId,
          plantingTypeId = PlantingType.Undo,
      )

      val reassignment =
          DeliveryStore.Reassignment(
              fromPlantingId = species1PlantingId,
              numPlants = 1,
              toPlantingSubzoneId = otherPlantingSubzoneId,
          )

      assertThrows<ReassignmentOfUndoNotAllowedException> {
        store.reassignDelivery(inserted.deliveryId, listOf(reassignment))
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
                    toPlantingSubzoneId = otherPlantingSubzoneId,
                ),
            ),
        )
      }
    }
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns delivery and plantings`() {
      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      val plantingId1 =
          insertPlanting(plantingSubzoneId = plantingSubzoneId, speciesId = speciesId1)
      val plantingId2 =
          insertPlanting(
              numPlants = 2,
              plantingSubzoneId = plantingSubzoneId,
              speciesId = speciesId2,
          )

      val expected =
          DeliveryModel(
              createdTime = Instant.EPOCH,
              id = deliveryId,
              plantings =
                  listOf(
                      PlantingModel(
                          id = plantingId1,
                          numPlants = 1,
                          speciesId = speciesId1,
                          plantingSubzoneId = plantingSubzoneId,
                          type = PlantingType.Delivery,
                      ),
                      PlantingModel(
                          id = plantingId2,
                          numPlants = 2,
                          speciesId = speciesId2,
                          plantingSubzoneId = plantingSubzoneId,
                          type = PlantingType.Delivery,
                      ),
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

  @Nested
  inner class UndoDelivery {
    @Test
    fun `creates new delivery that reverses original plantings including reassignments`() {
      val otherSubzoneId = insertPlantingSubzone(plantingZoneId = plantingZoneId)

      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertPlanting(numPlants = 5, plantingSubzoneId = plantingSubzoneId, speciesId = speciesId1)
      insertPlanting(numPlants = 2, plantingSubzoneId = plantingSubzoneId, speciesId = speciesId2)
      insertPlanting(
          numPlants = -1,
          plantingSubzoneId = plantingSubzoneId,
          speciesId = speciesId1,
          plantingTypeId = PlantingType.ReassignmentFrom,
      )
      insertPlanting(
          numPlants = 1,
          plantingSubzoneId = otherSubzoneId,
          speciesId = speciesId1,
          plantingTypeId = PlantingType.ReassignmentTo,
      )

      insertPlantingSitePopulation(plantingSiteId, speciesId1, 10, 9)
      insertPlantingSitePopulation(plantingSiteId, speciesId2, 9, 8)
      insertPlantingZonePopulation(plantingZoneId, speciesId1, 8, 7)
      insertPlantingZonePopulation(plantingZoneId, speciesId2, 6, 5)
      insertPlantingSubzonePopulation(plantingSubzoneId, speciesId1, 7, 6)
      insertPlantingSubzonePopulation(plantingSubzoneId, speciesId2, 5, 4)
      insertPlantingSubzonePopulation(otherSubzoneId, speciesId1, 3, 2)

      val undoWithdrawalId =
          insertNurseryWithdrawal(
              purpose = WithdrawalPurpose.Undo,
              undoesWithdrawalId = withdrawalId,
          )
      val undoDeliveryId = store.undoDelivery(deliveryId, undoWithdrawalId)

      val dummyPlantingId = PlantingId(1)
      val expectedPlantings =
          setOf(
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = -5,
                  plantingSubzoneId = plantingSubzoneId,
                  speciesId = speciesId1,
                  type = PlantingType.Undo,
              ),
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = -2,
                  plantingSubzoneId = plantingSubzoneId,
                  speciesId = speciesId2,
                  type = PlantingType.Undo,
              ),
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = 1,
                  plantingSubzoneId = plantingSubzoneId,
                  speciesId = speciesId1,
                  type = PlantingType.ReassignmentTo,
              ),
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = -1,
                  plantingSubzoneId = otherSubzoneId,
                  speciesId = speciesId1,
                  type = PlantingType.ReassignmentFrom,
              ),
          )

      val undoDelivery = store.fetchOneById(undoDeliveryId)

      assertEquals(plantingSiteId, undoDelivery.plantingSiteId, "Planting site ID")
      assertEquals(undoWithdrawalId, undoDelivery.withdrawalId, "Withdrawal ID")
      assertEquals(
          expectedPlantings,
          undoDelivery.plantings.map { it.copy(id = dummyPlantingId) }.toSet(),
          "Plantings",
      )

      assertSetEquals(
          setOf(
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 5, 4),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 7, 6),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )

      assertSetEquals(
          setOf(
              StratumPopulationsRow(plantingZoneId, speciesId1, 3, 2),
              StratumPopulationsRow(plantingZoneId, speciesId2, 4, 3),
          ),
          plantingZonePopulationsDao.findAll().toSet(),
          "Planting zone populations",
      )

      assertSetEquals(
          setOf(
              SubstratumPopulationsRow(plantingSubzoneId, speciesId1, 3, 2),
              SubstratumPopulationsRow(plantingSubzoneId, speciesId2, 3, 2),
              SubstratumPopulationsRow(otherSubzoneId, speciesId1, 2, 1),
          ),
          plantingSubzonePopulationsDao.findAll().toSet(),
          "Planting subzone populations",
      )
    }

    @Test
    fun `undoes delivery that did not specify planting subzones`() {
      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertPlanting(numPlants = 5, speciesId = speciesId1)
      insertPlanting(numPlants = 2, speciesId = speciesId2)

      insertPlantingSitePopulation(plantingSiteId, speciesId1, 10, 9)
      insertPlantingSitePopulation(plantingSiteId, speciesId2, 9, 8)

      val undoWithdrawalId =
          insertNurseryWithdrawal(
              purpose = WithdrawalPurpose.Undo,
              undoesWithdrawalId = withdrawalId,
          )
      val undoDeliveryId = store.undoDelivery(deliveryId, undoWithdrawalId)

      val dummyPlantingId = PlantingId(1)
      val expectedPlantings =
          setOf(
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = -5,
                  speciesId = speciesId1,
                  type = PlantingType.Undo,
              ),
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = -2,
                  speciesId = speciesId2,
                  type = PlantingType.Undo,
              ),
          )

      val undoDelivery = store.fetchOneById(undoDeliveryId)

      assertEquals(plantingSiteId, undoDelivery.plantingSiteId, "Planting site ID")
      assertEquals(undoWithdrawalId, undoDelivery.withdrawalId, "Withdrawal ID")
      assertEquals(
          expectedPlantings,
          undoDelivery.plantings.map { it.copy(id = dummyPlantingId) }.toSet(),
          "Plantings",
      )

      assertSetEquals(
          setOf(
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 5, 4),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 7, 6),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )
    }

    @Test
    fun `does not update plants since last observation if undoing withdrawal older than last observation`() {
      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertPlanting(numPlants = 5, speciesId = speciesId1)
      insertPlanting(numPlants = 2, speciesId = speciesId2)

      insertPlantingSitePopulation(plantingSiteId, speciesId1, 100, 10)
      insertPlantingSitePopulation(plantingSiteId, speciesId2, 90, 9)

      clock.instant = clock.instant.plus(1, ChronoUnit.DAYS)
      insertObservation(completedTime = clock.instant)

      clock.instant = clock.instant.plus(1, ChronoUnit.DAYS)
      val undoWithdrawalId =
          insertNurseryWithdrawal(
              createdTime = clock.instant,
              purpose = WithdrawalPurpose.Undo,
              undoesWithdrawalId = withdrawalId,
          )

      store.undoDelivery(deliveryId, undoWithdrawalId)

      assertSetEquals(
          setOf(
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 95, 10),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 88, 9),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )
    }

    @Test
    fun `throws exception if original delivery was already an undo`() {
      insertNurseryWithdrawal(purpose = WithdrawalPurpose.Undo, undoesWithdrawalId = withdrawalId)
      insertDelivery(plantingSiteId = plantingSiteId)
      insertPlanting(
          speciesId = speciesId1,
          numPlants = -1,
          plantingSiteId = plantingSiteId,
          plantingTypeId = PlantingType.Undo,
      )

      assertThrows<UndoOfUndoNotAllowedException> {
        store.undoDelivery(inserted.deliveryId, inserted.withdrawalId)
      }
    }

    @Test
    fun `throws exception if new withdrawal is not an undo`() {
      insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)

      assertThrows<WithdrawalNotUndoException> {
        store.undoDelivery(inserted.deliveryId, inserted.withdrawalId)
      }
    }

    @Test
    fun `throws exception if no permission to update original delivery`() {
      every { user.canUpdateDelivery(any()) } returns false

      insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertNurseryWithdrawal(purpose = WithdrawalPurpose.Undo, undoesWithdrawalId = withdrawalId)

      assertThrows<AccessDeniedException> {
        store.undoDelivery(inserted.deliveryId, inserted.withdrawalId)
      }
    }
  }
}
