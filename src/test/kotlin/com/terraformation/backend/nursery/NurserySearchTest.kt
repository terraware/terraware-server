package com.terraformation.backend.nursery

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class NurserySearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(BATCHES)

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables: SearchTables by lazy { SearchTables(clock) }
  private val numberFormat = NumberFormat.getIntegerInstance()

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization(organizationId)
    insertOrganizationUser(user.userId, organizationId, Role.Manager)
    insertFacility(facilityId, name = "Nursery", type = FacilityType.Nursery)
  }

  @Nested
  inner class SummaryTables {
    private val organizationId2 = OrganizationId(2)
    private val speciesId1 = SpeciesId(1)
    private val speciesId2 = SpeciesId(2)
    private val org2SpeciesId = SpeciesId(3)
    private val facilityId2 = FacilityId(200)
    private val org2FacilityId = FacilityId(300)
    private val subLocationId = SubLocationId(1)

    @BeforeEach
    fun insertBatches() {
      insertOrganization(organizationId2)
      insertOrganizationUser(user.userId, organizationId2, Role.Contributor)

      insertFacility(facilityId2, name = "Other Nursery", type = FacilityType.Nursery)
      insertFacility(
          org2FacilityId,
          name = "Other Org Nursery",
          organizationId = organizationId2,
          type = FacilityType.Nursery)
      insertSubLocation(subLocationId)

      insertSpecies(speciesId1)
      insertSpecies(speciesId2)
      insertSpecies(org2SpeciesId, organizationId = organizationId2)

      every { user.facilityRoles } returns
          mapOf(
              facilityId to Role.Manager,
              facilityId2 to Role.Manager,
              org2FacilityId to Role.Contributor)
      every { user.organizationRoles } returns
          mapOf(organizationId to Role.Manager, organizationId2 to Role.Contributor)

      insertBatch(
          addedDate = LocalDate.of(2021, 3, 4),
          facilityId = facilityId,
          germinatingQuantity = 1,
          notReadyQuantity = 2,
          readyQuantity = 4,
          speciesId = speciesId1,
      )
      insertBatchSubLocation()
      insertBatch(
          addedDate = LocalDate.of(2022, 9, 2),
          facilityId = facilityId,
          germinatingQuantity = 8,
          notReadyQuantity = 16,
          readyQuantity = 32,
          speciesId = speciesId1,
          version = 2,
      )
      insertBatch(
          BatchesRow(readyByDate = LocalDate.of(2022, 10, 2)),
          addedDate = LocalDate.of(2022, 9, 3),
          facilityId = facilityId2,
          germinatingQuantity = 64,
          notReadyQuantity = 128,
          readyQuantity = 256,
          speciesId = speciesId1,
      )
      insertBatch(
          addedDate = LocalDate.of(2022, 9, 4),
          facilityId = facilityId,
          germinatingQuantity = 512,
          notReadyQuantity = 1024,
          readyQuantity = 2048,
          speciesId = speciesId2,
      )
      insertBatch(
          addedDate = LocalDate.of(2022, 11, 15),
          organizationId = organizationId2,
          facilityId = org2FacilityId,
          germinatingQuantity = 4096,
          notReadyQuantity = 8192,
          readyQuantity = 16384,
          speciesId = org2SpeciesId,
      )
    }

    @Test
    fun `inventory tables return correct totals`() {
      val prefix = SearchFieldPrefix(root = searchTables.inventories)
      val results =
          searchService.search(
              prefix,
              listOf(
                  prefix.resolve("species_id"),
                  prefix.resolve("germinatingQuantity"),
                  prefix.resolve("notReadyQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
                  prefix.resolve("facilityInventories.facility_id"),
                  prefix.resolve("facilityInventories.facility_name"),
                  prefix.resolve("facilityInventories.germinatingQuantity"),
                  prefix.resolve("facilityInventories.notReadyQuantity"),
                  prefix.resolve("facilityInventories.readyQuantity"),
                  prefix.resolve("facilityInventories.totalQuantity"),
              ),
              FieldNode(prefix.resolve("organization_id"), listOf("$organizationId")),
              listOf(
                  SearchSortField(prefix.resolve("species_id")),
                  SearchSortField(prefix.resolve("facilityInventories.facility_id")),
              ))

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "species_id" to "1",
                      "germinatingQuantity" to number(1 + 8 + 64),
                      "notReadyQuantity" to number(2 + 16 + 128),
                      "readyQuantity" to number(4 + 32 + 256),
                      "totalQuantity" to number(2 + 4 + 16 + 32 + 128 + 256),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "facility_id" to "$facilityId",
                                  "facility_name" to "Nursery",
                                  "germinatingQuantity" to number(1 + 8),
                                  "notReadyQuantity" to number(2 + 16),
                                  "readyQuantity" to number(4 + 32),
                                  "totalQuantity" to number(2 + 4 + 16 + 32),
                              ),
                              mapOf(
                                  "facility_id" to "$facilityId2",
                                  "facility_name" to "Other Nursery",
                                  "germinatingQuantity" to number(64),
                                  "notReadyQuantity" to number(128),
                                  "readyQuantity" to number(256),
                                  "totalQuantity" to number(128 + 256),
                              ),
                          )),
                  mapOf(
                      "species_id" to "2",
                      "germinatingQuantity" to number(512),
                      "notReadyQuantity" to number(1024),
                      "readyQuantity" to number(2048),
                      "totalQuantity" to number(1024 + 2048),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "facility_id" to "$facilityId",
                                  "facility_name" to "Nursery",
                                  "germinatingQuantity" to number(512),
                                  "notReadyQuantity" to number(1024),
                                  "readyQuantity" to number(2048),
                                  "totalQuantity" to number(1024 + 2048),
                              ),
                          )),
              ),
              null),
          results)
    }

    @Test
    fun `facility inventory totals table returns correct totals`() {
      // Empty batch shouldn't count toward total species.
      insertBatch(facilityId = facilityId2, speciesId = speciesId2)

      val prefix = SearchFieldPrefix(root = searchTables.facilityInventoryTotals)
      val results =
          searchService.search(
              prefix,
              listOf(
                  prefix.resolve("facility_id"),
                  prefix.resolve("facility_name"),
                  prefix.resolve("facilityInventories.species_id"),
                  prefix.resolve("facilityInventories.species_scientificName"),
                  prefix.resolve("germinatingQuantity"),
                  prefix.resolve("notReadyQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
                  prefix.resolve("totalSpecies"),
              ),
              FieldNode(prefix.resolve("organization_id"), listOf("$organizationId")),
              listOf(SearchSortField(prefix.resolve("facility_id"))))

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "facility_id" to "$facilityId",
                      "facility_name" to "Nursery",
                      "germinatingQuantity" to number(1 + 8 + 512),
                      "notReadyQuantity" to number(2 + 16 + 1024),
                      "readyQuantity" to number(4 + 32 + 2048),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "species_id" to "$speciesId1",
                                  "species_scientificName" to "Species $speciesId1",
                              ),
                              mapOf(
                                  "species_id" to "$speciesId2",
                                  "species_scientificName" to "Species $speciesId2",
                              ),
                          ),
                      "totalQuantity" to number(2 + 4 + 16 + 32 + 1024 + 2048),
                      "totalSpecies" to number(2),
                  ),
                  mapOf(
                      "facility_id" to "$facilityId2",
                      "facility_name" to "Other Nursery",
                      "germinatingQuantity" to number(64),
                      "notReadyQuantity" to number(128),
                      "readyQuantity" to number(256),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "species_id" to "$speciesId1",
                                  "species_scientificName" to "Species $speciesId1",
                              ),
                          ),
                      "totalQuantity" to number(128 + 256),
                      "totalSpecies" to number(1),
                  ),
              ),
              null),
          results)
    }

    @Test
    fun `facility inventories table can be searched by facility`() {
      val prefix = SearchFieldPrefix(root = searchTables.facilityInventories)
      val results =
          searchService.search(
              prefix,
              listOf(
                  prefix.resolve("species_id"),
                  prefix.resolve("facility_id"),
                  prefix.resolve("facility_name"),
                  prefix.resolve("germinatingQuantity"),
                  prefix.resolve("notReadyQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
              ),
              FieldNode(prefix.resolve("facility_id"), listOf("$facilityId2")))

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "species_id" to "1",
                      "facility_id" to "$facilityId2",
                      "facility_name" to "Other Nursery",
                      "germinatingQuantity" to number(64),
                      "notReadyQuantity" to number(128),
                      "readyQuantity" to number(256),
                      "totalQuantity" to number(128 + 256)),
              ),
              null),
          results)
    }

    @Test
    fun `batches table returns correct totals`() {
      insertWithdrawal()
      insertBatchWithdrawal(
          batchId = 1,
          germinatingQuantityWithdrawn = 512,
          notReadyQuantityWithdrawn = 1024,
          readyQuantityWithdrawn = 2048,
      )
      insertBatchWithdrawal(
          batchId = 2,
          germinatingQuantityWithdrawn = 4096,
          notReadyQuantityWithdrawn = 8192,
          readyQuantityWithdrawn = 16384,
      )

      insertWithdrawal()
      insertBatchWithdrawal(
          batchId = 2,
          germinatingQuantityWithdrawn = 32768,
          notReadyQuantityWithdrawn = 65536,
          readyQuantityWithdrawn = 131072,
      )
      insertBatchWithdrawal(
          batchId = 4, // different species in same withdrawal; shouldn't be included in total
          germinatingQuantityWithdrawn = 262144,
          notReadyQuantityWithdrawn = 524288,
          readyQuantityWithdrawn = 1048576,
      )

      val prefix = SearchFieldPrefix(root = searchTables.batches)
      val results =
          searchService.search(
              prefix,
              listOf(
                  prefix.resolve("id"),
                  prefix.resolve("batchNumber"),
                  prefix.resolve("germinatingQuantity"),
                  prefix.resolve("notReadyQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
                  prefix.resolve("totalQuantityWithdrawn"),
                  prefix.resolve("facility_name"),
                  prefix.resolve("readyByDate"),
                  prefix.resolve("addedDate"),
                  prefix.resolve("subLocations.subLocation_id"),
                  prefix.resolve("version"),
              ),
              FieldNode(prefix.resolve("species_id"), listOf("$speciesId1")))

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1",
                      "batchNumber" to "1",
                      "germinatingQuantity" to number(1),
                      "notReadyQuantity" to number(2),
                      "readyQuantity" to number(4),
                      "totalQuantity" to number(2 + 4),
                      "totalQuantityWithdrawn" to number(1024 + 2048),
                      "facility_name" to "Nursery",
                      "addedDate" to "2021-03-04",
                      "subLocations" to
                          listOf(
                              mapOf(
                                  "subLocation_id" to "$subLocationId",
                              ),
                          ),
                      "version" to number(1),
                  ),
                  mapOf(
                      "id" to "2",
                      "batchNumber" to "2",
                      "germinatingQuantity" to number(8),
                      "notReadyQuantity" to number(16),
                      "readyQuantity" to number(32),
                      "totalQuantity" to number(16 + 32),
                      "totalQuantityWithdrawn" to number(8192 + 16384 + 65536 + 131072),
                      "facility_name" to "Nursery",
                      "addedDate" to "2022-09-02",
                      "version" to number(2),
                  ),
                  mapOf(
                      "id" to "3",
                      "batchNumber" to "3",
                      "germinatingQuantity" to number(64),
                      "notReadyQuantity" to number(128),
                      "readyQuantity" to number(256),
                      "totalQuantity" to number(128 + 256),
                      "totalQuantityWithdrawn" to number(0),
                      "facility_name" to "Other Nursery",
                      "readyByDate" to "2022-10-02",
                      "addedDate" to "2022-09-03",
                      "version" to number(1),
                  ),
              ),
              null),
          results)
    }

    @Test
    fun `withdrawals include correct calculated values`() {
      insertPlantingSite()
      insertPlantingZone()

      val facility1Species1BatchId = 1
      val facility1Species2BatchId = 4
      val facility2Species2BatchId =
          insertBatch(
              facilityId = facilityId2,
              germinatingQuantity = 1,
              notReadyQuantity = 2,
              readyQuantity = 4,
              speciesId = speciesId2,
          )

      val nurseryTransferWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.NurseryTransfer,
              destinationFacilityId = facilityId2,
              withdrawnDate = LocalDate.of(2021, 1, 1),
          )

      insertBatchWithdrawal(
          batchId = facility1Species1BatchId,
          destinationBatchId = 3,
          readyQuantityWithdrawn = 1,
      )
      insertBatchWithdrawal(
          batchId = facility1Species2BatchId,
          destinationBatchId = facility2Species2BatchId,
          notReadyQuantityWithdrawn = 2,
      )

      val otherWithdrawalId = insertWithdrawal(withdrawnDate = LocalDate.of(2022, 2, 2))

      insertBatchWithdrawal(
          batchId = facility1Species1BatchId,
          readyQuantityWithdrawn = 4,
      )

      val outplantWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.OutPlant, withdrawnDate = LocalDate.of(2023, 3, 3))

      insertBatchWithdrawal(
          batchId = facility1Species1BatchId,
          readyQuantityWithdrawn = 8,
      )
      insertBatchWithdrawal(
          batchId = facility1Species2BatchId,
          readyQuantityWithdrawn = 16,
      )

      val deliveryId = insertDelivery()
      insertPlantingSubzone()
      insertPlanting(numPlants = 8, speciesId = speciesId1)
      insertPlanting(numPlants = 16, speciesId = speciesId2)
      insertPlanting(
          numPlants = -1, plantingTypeId = PlantingType.ReassignmentFrom, speciesId = speciesId1)
      insertPlanting(
          numPlants = -3, plantingTypeId = PlantingType.ReassignmentFrom, speciesId = speciesId2)

      insertPlantingSubzone()
      insertPlanting(
          numPlants = 1, plantingTypeId = PlantingType.ReassignmentTo, speciesId = speciesId1)

      insertPlantingSubzone()
      insertPlanting(
          numPlants = 3, plantingTypeId = PlantingType.ReassignmentTo, speciesId = speciesId2)

      // Withdrawal for another organization shouldn't be visible.
      insertOrganization(3)
      insertFacility(3, 3)
      insertWithdrawal(facilityId = 3)

      val prefix = SearchFieldPrefix(root = searchTables.nurseryWithdrawals)
      val fields =
          listOf(
                  "batchWithdrawals.batch_species_scientificName",
                  "delivery_id",
                  "destinationFacilityId",
                  "destinationName",
                  "facility_name",
                  "hasReassignments",
                  "id",
                  "plantingSubzoneNames",
                  "purpose",
                  "totalWithdrawn",
                  "withdrawnDate",
              )
              .map { prefix.resolve(it) }
      val orderBy =
          listOf(
              SearchSortField(prefix.resolve("id")),
              SearchSortField(prefix.resolve("batchWithdrawals.batch_species_scientificName")),
          )

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "batchWithdrawals" to
                          listOf(
                              mapOf(
                                  "batch_species_scientificName" to "Species 1",
                              ),
                              mapOf(
                                  "batch_species_scientificName" to "Species 2",
                              ),
                          ),
                      "destinationFacilityId" to "$facilityId2",
                      "destinationName" to "Other Nursery",
                      "facility_name" to "Nursery",
                      "hasReassignments" to "false",
                      "id" to "$nurseryTransferWithdrawalId",
                      "purpose" to WithdrawalPurpose.NurseryTransfer.getDisplayName(Locale.ENGLISH),
                      "totalWithdrawn" to number(3),
                      "withdrawnDate" to "2021-01-01",
                  ),
                  mapOf(
                      "batchWithdrawals" to
                          listOf(
                              mapOf(
                                  "batch_species_scientificName" to "Species 1",
                              ),
                          ),
                      "facility_name" to "Nursery",
                      "hasReassignments" to "false",
                      "id" to "$otherWithdrawalId",
                      "purpose" to WithdrawalPurpose.Other.getDisplayName(Locale.ENGLISH),
                      "totalWithdrawn" to number(4),
                      "withdrawnDate" to "2022-02-02",
                  ),
                  mapOf(
                      "batchWithdrawals" to
                          listOf(
                              mapOf(
                                  "batch_species_scientificName" to "Species 1",
                              ),
                              mapOf(
                                  "batch_species_scientificName" to "Species 2",
                              ),
                          ),
                      "delivery_id" to "$deliveryId",
                      "destinationName" to "Site 1",
                      "facility_name" to "Nursery",
                      "hasReassignments" to "true",
                      "id" to "$outplantWithdrawalId",
                      "plantingSubzoneNames" to "Z1-1 (Z1-2, Z1-3)",
                      "purpose" to WithdrawalPurpose.OutPlant.getDisplayName(Locale.ENGLISH),
                      "totalWithdrawn" to number(24),
                      "withdrawnDate" to "2023-03-03",
                  ),
              ),
              null)

      val actual = searchService.search(prefix, fields, NoConditionNode(), orderBy)

      assertJsonEquals(expected, actual)
    }
  }

  /**
   * Renders a number using English formatting rules to match the search API's rendering of numeric
   * fields. 1024 becomes "1,024".
   */
  private fun number(value: Int) = numberFormat.format(value)
}
