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
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumPopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstratumPopulationsRow
import com.terraformation.backend.db.tracking.tables.records.DeliveriesRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSitePopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingsRecord
import com.terraformation.backend.db.tracking.tables.records.StratumPopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.SubstratumPopulationsRecord
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_POPULATIONS
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.UndoOfUndoNotAllowedException
import com.terraformation.backend.tracking.model.DeliveryModel
import com.terraformation.backend.tracking.model.PlantingModel
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

  private val plantingSiteId by lazy { insertPlantingSite() }
  private val stratumId by lazy { insertStratum(plantingSiteId = plantingSiteId) }
  private val substratumId by lazy { insertSubstratum(stratumId = stratumId) }
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
      insertPlantingSitePopulation(plantingSiteId, speciesId1, 6)
      insertStratumPopulation(stratumId, speciesId1, 4)
      insertSubstratumPopulation(substratumId, speciesId1, 2)

      val deliveryId =
          store.createDelivery(
              withdrawalId,
              plantingSiteId,
              substratumId,
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
                  substratumId = substratumId,
                  speciesId = speciesId1,
              ),
              PlantingsRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  deliveryId = deliveryId,
                  numPlants = 20,
                  plantingSiteId = plantingSiteId,
                  plantingTypeId = PlantingType.Delivery,
                  substratumId = substratumId,
                  speciesId = speciesId2,
              ),
          ),
          plantingsDao.findAll().map { it.copy(id = null) }.toSet(),
          "Plantings",
      )

      assertSetEquals(
          setOf(
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 21),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 20),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )

      assertSetEquals(
          setOf(
              StratumPopulationsRow(stratumId, speciesId1, 19),
              StratumPopulationsRow(stratumId, speciesId2, 20),
          ),
          stratumPopulationsDao.findAll().toSet(),
          "Stratum populations",
      )

      assertSetEquals(
          setOf(
              SubstratumPopulationsRow(substratumId, speciesId1, 17),
              SubstratumPopulationsRow(substratumId, speciesId2, 20),
          ),
          substratumPopulationsDao.findAll().toSet(),
          "Substratum populations",
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
    fun `requires substratum ID if planting site has substrata`() {
      // Cause the substratum to be inserted by lazy evaluation
      assertNotNull(substratumId)

      assertThrows<DeliveryMissingSubstratumException> {
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
          substratumId,
          mapOf(speciesId1 to 100, speciesId2 to 100),
      )
    }
    private val species1PlantingId: PlantingId by lazy {
      plantingsDao.fetchByDeliveryId(deliveryId).first { it.speciesId == speciesId1 }.id!!
    }
    private val species2PlantingId: PlantingId by lazy {
      plantingsDao.fetchByDeliveryId(deliveryId).first { it.speciesId == speciesId2 }.id!!
    }
    private val otherSubstratumId: SubstratumId by lazy { insertSubstratum(stratumId = stratumId) }
    private val otherPlantingSiteId: PlantingSiteId by lazy { insertPlantingSite() }
    private val otherSiteStratumId: StratumId by lazy {
      insertStratum(plantingSiteId = otherPlantingSiteId)
    }
    private val otherSiteSubstratumId: SubstratumId by lazy {
      insertSubstratum(stratumId = otherSiteStratumId)
    }

    @Test
    fun `creates reassignment plantings`() {
      insertPlantingSitePopulation(plantingSiteId, speciesId1, 6)
      insertStratumPopulation(stratumId, speciesId1, 4)
      insertSubstratumPopulation(substratumId, speciesId1, 2)

      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = species1PlantingId,
                  numPlants = 1,
                  notes = "notes 1",
                  toSubstratumId = otherSubstratumId,
              ),
              DeliveryStore.Reassignment(
                  fromPlantingId = species2PlantingId,
                  numPlants = 2,
                  notes = "notes 2",
                  toSubstratumId = otherSubstratumId,
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
                  substratumId = substratumId,
                  speciesId = speciesId1,
                  numPlants = -1,
              ),
              commonValues.copy(
                  notes = "notes 1",
                  plantingTypeId = PlantingType.ReassignmentTo,
                  substratumId = otherSubstratumId,
                  speciesId = speciesId1,
                  numPlants = 1,
              ),
              commonValues.copy(
                  plantingTypeId = PlantingType.ReassignmentFrom,
                  substratumId = substratumId,
                  speciesId = speciesId2,
                  numPlants = -2,
              ),
              commonValues.copy(
                  notes = "notes 2",
                  plantingTypeId = PlantingType.ReassignmentTo,
                  substratumId = otherSubstratumId,
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
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 106),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 100),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )

      assertSetEquals(
          setOf(
              StratumPopulationsRow(stratumId, speciesId1, 104),
              StratumPopulationsRow(stratumId, speciesId2, 100),
          ),
          stratumPopulationsDao.findAll().toSet(),
          "Stratum populations",
      )

      assertSetEquals(
          setOf(
              SubstratumPopulationsRow(substratumId, speciesId1, 101),
              SubstratumPopulationsRow(substratumId, speciesId2, 98),
              SubstratumPopulationsRow(otherSubstratumId, speciesId1, 1),
              SubstratumPopulationsRow(otherSubstratumId, speciesId2, 2),
          ),
          substratumPopulationsDao.findAll().toSet(),
          "Substratum populations",
      )
    }

    @Test
    fun `creates a delivery at the destination site for a cross-site reassignment`() {
      assertNotNull(species1PlantingId)

      val plantingsBeforeReassignment = dslContext.fetch(PLANTINGS).onEach { it.id = null }

      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = species1PlantingId,
                  numPlants = 30,
                  notes = "moved sites",
                  toSubstratumId = otherSiteSubstratumId,
              )
          ),
      )

      assertTableEquals(
          DeliveriesRecord(
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              plantingSiteId = otherPlantingSiteId,
              reassignedFromDeliveryId = deliveryId,
              withdrawalId = withdrawalId,
          ),
          where = DELIVERIES.REASSIGNED_FROM_DELIVERY_ID.eq(deliveryId),
      )

      val reassignmentDeliveryId =
          deliveriesDao.fetchByReassignedFromDeliveryId(deliveryId).single().id!!

      assertTableEquals(
          plantingsBeforeReassignment +
              listOf(
                  PlantingsRecord(
                      createdBy = user.userId,
                      createdTime = clock.instant,
                      deliveryId = deliveryId,
                      numPlants = -30,
                      plantingSiteId = plantingSiteId,
                      plantingTypeId = PlantingType.ReassignmentFrom,
                      speciesId = speciesId1,
                      substratumId = substratumId,
                  ),
                  PlantingsRecord(
                      createdBy = user.userId,
                      createdTime = clock.instant,
                      deliveryId = reassignmentDeliveryId,
                      notes = "moved sites",
                      numPlants = 30,
                      plantingSiteId = otherPlantingSiteId,
                      plantingTypeId = PlantingType.ReassignmentTo,
                      speciesId = speciesId1,
                      substratumId = otherSiteSubstratumId,
                  ),
              ),
      )
    }

    @Test
    fun `reuses the destination delivery across two reassignment calls`() {
      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = species1PlantingId,
                  numPlants = 10,
                  toSubstratumId = otherSiteSubstratumId,
              )
          ),
      )

      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = species2PlantingId,
                  numPlants = 20,
                  toSubstratumId = otherSiteSubstratumId,
              )
          ),
      )

      assertEquals(
          1,
          deliveriesDao.fetchByReassignedFromDeliveryId(deliveryId).size,
          "Number of reassignment deliveries",
      )
    }

    @Test
    fun `moves populations between planting sites`() {
      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = species1PlantingId,
                  numPlants = 30,
                  toSubstratumId = otherSiteSubstratumId,
              )
          ),
      )

      assertTableEquals(
          listOf(
              PlantingSitePopulationsRecord(plantingSiteId, speciesId1, 70),
              PlantingSitePopulationsRecord(plantingSiteId, speciesId2, 100),
              PlantingSitePopulationsRecord(otherPlantingSiteId, speciesId1, 30),
          )
      )
      assertTableEquals(
          listOf(
              StratumPopulationsRecord(stratumId, speciesId1, 70),
              StratumPopulationsRecord(stratumId, speciesId2, 100),
              StratumPopulationsRecord(otherSiteStratumId, speciesId1, 30),
          )
      )
      assertTableEquals(
          listOf(
              SubstratumPopulationsRecord(substratumId, speciesId1, 70),
              SubstratumPopulationsRecord(substratumId, speciesId2, 100),
              SubstratumPopulationsRecord(otherSiteSubstratumId, speciesId1, 30),
          )
      )
    }

    @Test
    fun `throws exception if plantings are from a different delivery`() {
      val otherWithdrawalId = insertNurseryWithdrawal()
      val otherDeliveryId =
          store.createDelivery(
              otherWithdrawalId,
              plantingSiteId,
              substratumId,
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
                    toSubstratumId = otherSubstratumId,
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
                    toSubstratumId = otherSubstratumId,
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
                    toSubstratumId = substratumId,
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
                    toSubstratumId = otherSubstratumId,
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
              toSubstratumId = otherSubstratumId,
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
              toSubstratumId = otherSubstratumId,
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
              toSubstratumId = otherSubstratumId,
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
                    toSubstratumId = otherSubstratumId,
                ),
            ),
        )
      }
    }

    @Test
    fun `throws exception if destination substratum is in another organization`() {
      // Force the original delivery's planting site to be created by lazy evaluation
      assertNotNull(species1PlantingId)

      insertOrganization()
      insertPlantingSite()
      insertStratum()
      val otherOrgSubstratumId = insertSubstratum()

      assertThrows<CrossOrganizationReassignmentNotAllowedException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = species1PlantingId,
                    numPlants = 1,
                    toSubstratumId = otherOrgSubstratumId,
                )
            ),
        )
      }
    }

    @Test
    fun `throws exception if no permission to create deliveries at the destination site`() {
      // Force the destination planting site to be created by lazy evaluation
      assertNotNull(otherSiteSubstratumId)

      every { user.canCreateDelivery(otherPlantingSiteId) } returns false

      assertThrows<AccessDeniedException> {
        store.reassignDelivery(
            deliveryId,
            listOf(
                DeliveryStore.Reassignment(
                    fromPlantingId = species1PlantingId,
                    numPlants = 1,
                    toSubstratumId = otherSiteSubstratumId,
                )
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
      val plantingId1 = insertPlanting(substratumId = substratumId, speciesId = speciesId1)
      val plantingId2 =
          insertPlanting(
              numPlants = 2,
              substratumId = substratumId,
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
                          substratumId = substratumId,
                          type = PlantingType.Delivery,
                      ),
                      PlantingModel(
                          id = plantingId2,
                          numPlants = 2,
                          speciesId = speciesId2,
                          substratumId = substratumId,
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

    @Test
    fun `returns IDs of deliveries reassigned out of this one`() {
      val otherPlantingSiteId = insertPlantingSite()
      val originalDeliveryId =
          insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertPlanting(substratumId = substratumId, speciesId = speciesId1)
      val reassignmentDeliveryId =
          insertDelivery(
              row = DeliveriesRow(reassignedFromDeliveryId = originalDeliveryId),
              plantingSiteId = otherPlantingSiteId,
              withdrawalId = withdrawalId,
          )

      assertEquals(
          listOf(reassignmentDeliveryId),
          store.fetchOneById(originalDeliveryId).reassignmentDeliveryIds,
          "Reassignment delivery IDs on original delivery",
      )
      assertEquals(
          emptyList<DeliveryId>(),
          store.fetchOneById(reassignmentDeliveryId).reassignmentDeliveryIds,
          "Reassignment delivery IDs on reassignment delivery",
      )
    }

    @Test
    fun `fetchOneByWithdrawalId returns the original delivery when a withdrawal spans two sites`() {
      val otherPlantingSiteId = insertPlantingSite()
      val originalDeliveryId =
          insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertDelivery(
          row = DeliveriesRow(reassignedFromDeliveryId = originalDeliveryId),
          plantingSiteId = otherPlantingSiteId,
          withdrawalId = withdrawalId,
      )

      assertEquals(originalDeliveryId, store.fetchOneByWithdrawalId(withdrawalId)?.id)
    }
  }

  @Nested
  inner class UndoDelivery {
    @Test
    fun `creates new delivery that reverses original plantings including reassignments`() {
      val otherSubstratumId = insertSubstratum(stratumId = stratumId)

      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertPlanting(numPlants = 5, substratumId = substratumId, speciesId = speciesId1)
      insertPlanting(numPlants = 2, substratumId = substratumId, speciesId = speciesId2)
      insertPlanting(
          numPlants = -1,
          substratumId = substratumId,
          speciesId = speciesId1,
          plantingTypeId = PlantingType.ReassignmentFrom,
      )
      insertPlanting(
          numPlants = 1,
          substratumId = otherSubstratumId,
          speciesId = speciesId1,
          plantingTypeId = PlantingType.ReassignmentTo,
      )

      insertPlantingSitePopulation(plantingSiteId, speciesId1, 10)
      insertPlantingSitePopulation(plantingSiteId, speciesId2, 9)
      insertStratumPopulation(stratumId, speciesId1, 8)
      insertStratumPopulation(stratumId, speciesId2, 6)
      insertSubstratumPopulation(substratumId, speciesId1, 7)
      insertSubstratumPopulation(substratumId, speciesId2, 5)
      insertSubstratumPopulation(otherSubstratumId, speciesId1, 3)

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
                  substratumId = substratumId,
                  speciesId = speciesId1,
                  type = PlantingType.Undo,
              ),
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = -2,
                  substratumId = substratumId,
                  speciesId = speciesId2,
                  type = PlantingType.Undo,
              ),
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = 1,
                  substratumId = substratumId,
                  speciesId = speciesId1,
                  type = PlantingType.ReassignmentTo,
              ),
              PlantingModel(
                  id = dummyPlantingId,
                  numPlants = -1,
                  substratumId = otherSubstratumId,
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
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 5),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 7),
          ),
          plantingSitePopulationsDao.findAll().toSet(),
          "Planting site populations",
      )

      assertSetEquals(
          setOf(
              StratumPopulationsRow(stratumId, speciesId1, 3),
              StratumPopulationsRow(stratumId, speciesId2, 4),
          ),
          stratumPopulationsDao.findAll().toSet(),
          "Stratum populations",
      )

      assertSetEquals(
          setOf(
              SubstratumPopulationsRow(substratumId, speciesId1, 3),
              SubstratumPopulationsRow(substratumId, speciesId2, 3),
              SubstratumPopulationsRow(otherSubstratumId, speciesId1, 2),
          ),
          substratumPopulationsDao.findAll().toSet(),
          "Substratum populations",
      )
    }

    @Test
    fun `undoes delivery that did not specify substrata`() {
      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertPlanting(numPlants = 5, speciesId = speciesId1)
      insertPlanting(numPlants = 2, speciesId = speciesId2)

      insertPlantingSitePopulation(plantingSiteId, speciesId1, 10)
      insertPlantingSitePopulation(plantingSiteId, speciesId2, 9)

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
              PlantingSitePopulationsRow(plantingSiteId, speciesId1, 5),
              PlantingSitePopulationsRow(plantingSiteId, speciesId2, 7),
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

    @Test
    fun `undoes deliveries at both sites of a cross-site reassignment`() {
      val otherPlantingSiteId = insertPlantingSite()
      val otherSiteStratumId = insertStratum(plantingSiteId = otherPlantingSiteId)
      val otherSiteSubstratumId = insertSubstratum(stratumId = otherSiteStratumId)

      val deliveryId =
          store.createDelivery(withdrawalId, plantingSiteId, substratumId, mapOf(speciesId1 to 100))
      val plantingId = plantingsDao.findAll().single().id!!

      store.reassignDelivery(
          deliveryId,
          listOf(
              DeliveryStore.Reassignment(
                  fromPlantingId = plantingId,
                  numPlants = 30,
                  toSubstratumId = otherSiteSubstratumId,
              )
          ),
      )

      val deliveriesBeforeUndo = dslContext.fetch(DELIVERIES).onEach { it.id = null }

      val undoWithdrawalId =
          insertNurseryWithdrawal(
              purpose = WithdrawalPurpose.Undo,
              undoesWithdrawalId = withdrawalId,
          )

      val undoDeliveryId = store.undoDelivery(deliveryId, undoWithdrawalId)

      assertTableEquals(
          deliveriesBeforeUndo +
              listOf(
                  DeliveriesRecord(
                      createdBy = user.userId,
                      createdTime = clock.instant,
                      modifiedBy = user.userId,
                      modifiedTime = clock.instant,
                      plantingSiteId = plantingSiteId,
                      withdrawalId = undoWithdrawalId,
                  ),
                  DeliveriesRecord(
                      createdBy = user.userId,
                      createdTime = clock.instant,
                      modifiedBy = user.userId,
                      modifiedTime = clock.instant,
                      plantingSiteId = otherPlantingSiteId,
                      reassignedFromDeliveryId = undoDeliveryId,
                      withdrawalId = undoWithdrawalId,
                  ),
              ),
      )

      assertTableEmpty(PLANTING_SITE_POPULATIONS)
      assertTableEmpty(STRATUM_POPULATIONS)
      assertTableEmpty(SUBSTRATUM_POPULATIONS)
    }
  }
}
