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
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.AndNode
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

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables: SearchTables by lazy { SearchTables(clock) }
  private val numberFormat = NumberFormat.getIntegerInstance()

  private lateinit var facilityId: FacilityId
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(user.userId, organizationId, Role.Manager)
    facilityId = insertFacility(name = "Nursery", type = FacilityType.Nursery)
  }

  @Nested
  inner class SummaryTables {
    private lateinit var organizationId2: OrganizationId
    private lateinit var speciesId1: SpeciesId
    private lateinit var speciesId2: SpeciesId
    private lateinit var org2SpeciesId: SpeciesId
    private lateinit var facilityId2: FacilityId
    private lateinit var org2FacilityId: FacilityId
    private lateinit var subLocationId: SubLocationId
    private lateinit var batchId1: BatchId
    private lateinit var batchId2: BatchId
    private lateinit var batchId3: BatchId
    private lateinit var batchId4: BatchId
    private lateinit var org2BatchId: BatchId

    @BeforeEach
    fun insertBatches() {
      speciesId1 = insertSpecies()
      speciesId2 = insertSpecies()
      subLocationId = insertSubLocation()

      facilityId2 = insertFacility(name = "Other Nursery", type = FacilityType.Nursery)

      organizationId2 = insertOrganization()
      insertOrganizationUser(user.userId, role = Role.Contributor)
      org2FacilityId = insertFacility(name = "Other Org Nursery", type = FacilityType.Nursery)

      org2SpeciesId = insertSpecies()

      every { user.facilityRoles } returns
          mapOf(
              facilityId to Role.Manager,
              facilityId2 to Role.Manager,
              org2FacilityId to Role.Contributor,
          )
      every { user.organizationRoles } returns
          mapOf(organizationId to Role.Manager, organizationId2 to Role.Contributor)

      batchId1 =
          insertBatch(
              addedDate = LocalDate.of(2021, 3, 4),
              organizationId = organizationId,
              facilityId = facilityId,
              germinatingQuantity = 1,
              activeGrowthQuantity = 2,
              readyQuantity = 4,
              hardeningOffQuantity = 8,
              speciesId = speciesId1,
          )
      insertBatchSubLocation()
      batchId2 =
          insertBatch(
              addedDate = LocalDate.of(2022, 9, 2),
              organizationId = organizationId,
              facilityId = facilityId,
              germinatingQuantity = 8,
              activeGrowthQuantity = 16,
              readyQuantity = 32,
              hardeningOffQuantity = 64,
              speciesId = speciesId1,
              version = 2,
          )
      batchId3 =
          insertBatch(
              BatchesRow(
                  germinationStartedDate = LocalDate.of(2022, 9, 15),
                  readyByDate = LocalDate.of(2022, 10, 2),
                  seedsSownDate = LocalDate.of(2022, 9, 10),
              ),
              addedDate = LocalDate.of(2022, 9, 3),
              organizationId = organizationId,
              facilityId = facilityId2,
              germinatingQuantity = 64,
              activeGrowthQuantity = 128,
              readyQuantity = 256,
              hardeningOffQuantity = 512,
              speciesId = speciesId1,
          )
      batchId4 =
          insertBatch(
              addedDate = LocalDate.of(2022, 9, 4),
              organizationId = organizationId,
              facilityId = facilityId,
              germinatingQuantity = 512,
              activeGrowthQuantity = 1024,
              readyQuantity = 2048,
              hardeningOffQuantity = 4096,
              speciesId = speciesId2,
          )
      org2BatchId =
          insertBatch(
              addedDate = LocalDate.of(2022, 11, 15),
              organizationId = organizationId2,
              facilityId = org2FacilityId,
              germinatingQuantity = 4096,
              activeGrowthQuantity = 8192,
              readyQuantity = 16384,
              hardeningOffQuantity = 32768,
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
                  prefix.resolve("hardeningOffQuantity"),
                  prefix.resolve("activeGrowthQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
                  prefix.resolve("facilityInventories.facility_id"),
                  prefix.resolve("facilityInventories.facility_name"),
                  prefix.resolve("facilityInventories.germinatingQuantity"),
                  prefix.resolve("facilityInventories.hardeningOffQuantity"),
                  prefix.resolve("facilityInventories.activeGrowthQuantity"),
                  prefix.resolve("facilityInventories.readyQuantity"),
                  prefix.resolve("facilityInventories.totalQuantity"),
              ),
              mapOf(
                  prefix to FieldNode(prefix.resolve("organization_id"), listOf("$organizationId"))
              ),
              listOf(
                  SearchSortField(prefix.resolve("species_id")),
                  SearchSortField(prefix.resolve("facilityInventories.facility_id")),
              ),
          )

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "species_id" to "$speciesId1",
                      "germinatingQuantity" to number(1 + 8 + 64),
                      "hardeningOffQuantity" to number(8 + 64 + 512),
                      "activeGrowthQuantity" to number(2 + 16 + 128),
                      "readyQuantity" to number(4 + 32 + 256),
                      "totalQuantity" to number(2 + 4 + 8 + 16 + 32 + 64 + 128 + 256 + 512),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "facility_id" to "$facilityId",
                                  "facility_name" to "Nursery",
                                  "germinatingQuantity" to number(1 + 8),
                                  "hardeningOffQuantity" to number(8 + 64),
                                  "activeGrowthQuantity" to number(2 + 16),
                                  "readyQuantity" to number(4 + 32),
                                  "totalQuantity" to number(2 + 4 + 8 + 16 + 32 + 64),
                              ),
                              mapOf(
                                  "facility_id" to "$facilityId2",
                                  "facility_name" to "Other Nursery",
                                  "germinatingQuantity" to number(64),
                                  "hardeningOffQuantity" to number(512),
                                  "activeGrowthQuantity" to number(128),
                                  "readyQuantity" to number(256),
                                  "totalQuantity" to number(128 + 256 + 512),
                              ),
                          ),
                  ),
                  mapOf(
                      "species_id" to "$speciesId2",
                      "germinatingQuantity" to number(512),
                      "hardeningOffQuantity" to number(4096),
                      "activeGrowthQuantity" to number(1024),
                      "readyQuantity" to number(2048),
                      "totalQuantity" to number(1024 + 2048 + 4096),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "facility_id" to "$facilityId",
                                  "facility_name" to "Nursery",
                                  "germinatingQuantity" to number(512),
                                  "hardeningOffQuantity" to number(4096),
                                  "activeGrowthQuantity" to number(1024),
                                  "readyQuantity" to number(2048),
                                  "totalQuantity" to number(1024 + 2048 + 4096),
                              ),
                          ),
                  ),
              )
          ),
          results,
      )
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
                  prefix.resolve("hardeningOffQuantity"),
                  prefix.resolve("activeGrowthQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
                  prefix.resolve("totalSpecies"),
              ),
              mapOf(
                  prefix to FieldNode(prefix.resolve("organization_id"), listOf("$organizationId"))
              ),
              listOf(SearchSortField(prefix.resolve("facility_id"))),
          )

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "facility_id" to "$facilityId",
                      "facility_name" to "Nursery",
                      "germinatingQuantity" to number(1 + 8 + 512),
                      "hardeningOffQuantity" to number(8 + 64 + 4096),
                      "activeGrowthQuantity" to number(2 + 16 + 1024),
                      "readyQuantity" to number(4 + 32 + 2048),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "species_id" to "$speciesId1",
                                  "species_scientificName" to "Species 1",
                              ),
                              mapOf(
                                  "species_id" to "$speciesId2",
                                  "species_scientificName" to "Species 2",
                              ),
                          ),
                      "totalQuantity" to number(2 + 4 + 8 + 16 + 32 + 64 + 1024 + 2048 + 4096),
                      "totalSpecies" to number(2),
                  ),
                  mapOf(
                      "facility_id" to "$facilityId2",
                      "facility_name" to "Other Nursery",
                      "germinatingQuantity" to number(64),
                      "hardeningOffQuantity" to number(512),
                      "activeGrowthQuantity" to number(128),
                      "readyQuantity" to number(256),
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "species_id" to "$speciesId1",
                                  "species_scientificName" to "Species 1",
                              ),
                          ),
                      "totalQuantity" to number(128 + 256 + 512),
                      "totalSpecies" to number(1),
                  ),
              )
          ),
          results,
      )
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
                  prefix.resolve("hardeningOffQuantity"),
                  prefix.resolve("activeGrowthQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
              ),
              mapOf(prefix to FieldNode(prefix.resolve("facility_id"), listOf("$facilityId2"))),
          )

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "species_id" to "$speciesId1",
                      "facility_id" to "$facilityId2",
                      "facility_name" to "Other Nursery",
                      "germinatingQuantity" to number(64),
                      "hardeningOffQuantity" to number(512),
                      "activeGrowthQuantity" to number(128),
                      "readyQuantity" to number(256),
                      "totalQuantity" to number(128 + 256 + 512),
                  )
              )
          ),
          results,
      )
    }

    @Test
    fun `batches table returns correct totals`() {
      insertNurseryWithdrawal(facilityId = facilityId)
      insertBatchWithdrawal(
          batchId = batchId1,
          germinatingQuantityWithdrawn = 512,
          activeGrowthQuantityWithdrawn = 1024,
          readyQuantityWithdrawn = 2048,
          hardeningOffQuantityWithdrawn = 4096,
      )
      insertBatchWithdrawal(
          batchId = batchId2,
          germinatingQuantityWithdrawn = 4096,
          activeGrowthQuantityWithdrawn = 8192,
          readyQuantityWithdrawn = 16384,
          hardeningOffQuantityWithdrawn = 32768,
      )

      insertNurseryWithdrawal(facilityId = facilityId)
      insertBatchWithdrawal(
          batchId = batchId2,
          germinatingQuantityWithdrawn = 32768,
          activeGrowthQuantityWithdrawn = 65536,
          readyQuantityWithdrawn = 131072,
          hardeningOffQuantityWithdrawn = 262144,
      )
      insertBatchWithdrawal(
          batchId =
              batchId4, // different species in same withdrawal; shouldn't be included in total
          germinatingQuantityWithdrawn = 262144,
          activeGrowthQuantityWithdrawn = 524288,
          readyQuantityWithdrawn = 1048576,
          hardeningOffQuantityWithdrawn = 2097152,
      )

      val prefix = SearchFieldPrefix(root = searchTables.batches)
      val results =
          searchService.search(
              prefix,
              listOf(
                  prefix.resolve("id"),
                  prefix.resolve("batchNumber"),
                  prefix.resolve("germinatingQuantity"),
                  prefix.resolve("hardeningOffQuantity"),
                  prefix.resolve("activeGrowthQuantity"),
                  prefix.resolve("readyQuantity"),
                  prefix.resolve("totalQuantity"),
                  prefix.resolve("totalQuantityWithdrawn"),
                  prefix.resolve("facility_name"),
                  prefix.resolve("addedDate"),
                  prefix.resolve("germinationStartedDate"),
                  prefix.resolve("readyByDate"),
                  prefix.resolve("seedsSownDate"),
                  prefix.resolve("subLocations.subLocation_id"),
                  prefix.resolve("version"),
              ),
              mapOf(prefix to FieldNode(prefix.resolve("species_id"), listOf("$speciesId1"))),
          )

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "$batchId1",
                      "batchNumber" to "1",
                      "germinatingQuantity" to number(1),
                      "hardeningOffQuantity" to number(8),
                      "activeGrowthQuantity" to number(2),
                      "readyQuantity" to number(4),
                      "totalQuantity" to number(2 + 4 + 8),
                      "totalQuantityWithdrawn" to number(1024 + 2048 + 4096),
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
                      "id" to "$batchId2",
                      "batchNumber" to "2",
                      "germinatingQuantity" to number(8),
                      "hardeningOffQuantity" to number(64),
                      "activeGrowthQuantity" to number(16),
                      "readyQuantity" to number(32),
                      "totalQuantity" to number(16 + 32 + 64),
                      "totalQuantityWithdrawn" to
                          number(8192 + 16384 + 32768 + 65536 + 131072 + 262144),
                      "facility_name" to "Nursery",
                      "addedDate" to "2022-09-02",
                      "version" to number(2),
                  ),
                  mapOf(
                      "id" to "$batchId3",
                      "batchNumber" to "3",
                      "germinatingQuantity" to number(64),
                      "hardeningOffQuantity" to number(512),
                      "activeGrowthQuantity" to number(128),
                      "readyQuantity" to number(256),
                      "totalQuantity" to number(128 + 256 + 512),
                      "totalQuantityWithdrawn" to number(0),
                      "facility_name" to "Other Nursery",
                      "addedDate" to "2022-09-03",
                      "germinationStartedDate" to "2022-09-15",
                      "readyByDate" to "2022-10-02",
                      "seedsSownDate" to "2022-09-10",
                      "version" to number(1),
                  ),
              )
          ),
          results,
      )
    }

    @Test
    fun `can search for batches without projects`() {
      insertProject(name = "Project")
      batchesDao.update(batchesDao.findAll().map { it.copy(projectId = inserted.projectId) })
      val nonProjectBatchId = insertBatch(facilityId = facilityId)

      val prefix = SearchFieldPrefix(root = searchTables.batches)
      val results =
          searchService.search(
              prefix,
              listOf(
                  prefix.resolve("id"),
                  prefix.resolve("project_name"),
              ),
              mapOf(
                  prefix to
                      AndNode(
                          listOf(
                              FieldNode(
                                  prefix.resolve("facility_organization_id"),
                                  listOf("$organizationId"),
                              ),
                              FieldNode(prefix.resolve("project_id"), listOf(null)),
                          )
                      )
              ),
          )

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "$nonProjectBatchId",
                  )
              )
          ),
          results,
      )
    }

    @Test
    fun `withdrawals include correct calculated values`() {
      insertPlantingSite()
      insertPlantingZone()

      val facility1Species1BatchId = batchId1
      val facility1Species2BatchId = batchId4
      val facility2Species2BatchId =
          insertBatch(
              facilityId = facilityId2,
              germinatingQuantity = 1,
              activeGrowthQuantity = 2,
              readyQuantity = 4,
              speciesId = speciesId2,
          )

      val nurseryTransferWithdrawalId =
          insertNurseryWithdrawal(
              facilityId = facilityId,
              purpose = WithdrawalPurpose.NurseryTransfer,
              destinationFacilityId = facilityId2,
              withdrawnDate = LocalDate.of(2021, 1, 1),
          )

      insertBatchWithdrawal(
          batchId = facility1Species1BatchId,
          destinationBatchId = batchId3,
          readyQuantityWithdrawn = 1,
          hardeningOffQuantityWithdrawn = 3,
      )
      insertBatchWithdrawal(
          batchId = facility1Species2BatchId,
          destinationBatchId = facility2Species2BatchId,
          activeGrowthQuantityWithdrawn = 2,
      )

      val otherWithdrawalId =
          insertNurseryWithdrawal(facilityId = facilityId, withdrawnDate = LocalDate.of(2022, 2, 2))

      insertBatchWithdrawal(
          batchId = facility1Species1BatchId,
          readyQuantityWithdrawn = 4,
      )

      val outplantWithdrawalId =
          insertNurseryWithdrawal(
              facilityId = facilityId,
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.of(2023, 3, 3),
          )

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
          numPlants = -1,
          plantingTypeId = PlantingType.ReassignmentFrom,
          speciesId = speciesId1,
      )
      insertPlanting(
          numPlants = -3,
          plantingTypeId = PlantingType.ReassignmentFrom,
          speciesId = speciesId2,
      )

      insertPlantingSubzone()
      insertPlanting(
          numPlants = 1,
          plantingTypeId = PlantingType.ReassignmentTo,
          speciesId = speciesId1,
      )

      insertPlantingSubzone()
      insertPlanting(
          numPlants = 3,
          plantingTypeId = PlantingType.ReassignmentTo,
          speciesId = speciesId2,
      )

      // Withdrawal for another organization shouldn't be visible.
      insertOrganization()
      insertFacility()
      insertNurseryWithdrawal()

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
                      "totalWithdrawn" to number(6),
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
              )
          )

      val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()), orderBy)

      assertJsonEquals(expected, actual)
    }

    @Test
    fun `undo relationships can be queried from both directions`() {
      insertBatch()
      val withdrawalId =
          insertNurseryWithdrawal(
              facilityId = facilityId,
              withdrawnDate = LocalDate.of(2024, 1, 15),
          )
      insertBatchWithdrawal(germinatingQuantityWithdrawn = 1)
      val undoWithdrawalId =
          insertNurseryWithdrawal(
              facilityId = facilityId,
              purpose = WithdrawalPurpose.Undo,
              undoesWithdrawalId = withdrawalId,
              withdrawnDate = LocalDate.of(2024, 2, 5),
          )
      insertBatchWithdrawal(germinatingQuantityWithdrawn = -1)

      val prefix = SearchFieldPrefix(searchTables.nurseryWithdrawals)
      val fields =
          listOf(
                  "id",
                  "totalWithdrawn",
                  "undoesWithdrawalDate",
                  "undoesWithdrawalId",
                  "undoneByWithdrawalDate",
                  "undoneByWithdrawalId",
              )
              .map { prefix.resolve(it) }

      val orderBy = listOf(SearchSortField(prefix.resolve("id")))

      val expected =
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "$withdrawalId",
                      "totalWithdrawn" to "1",
                      "undoneByWithdrawalDate" to "2024-02-05",
                      "undoneByWithdrawalId" to "$undoWithdrawalId",
                  ),
                  mapOf(
                      "id" to "$undoWithdrawalId",
                      "totalWithdrawn" to "-1",
                      "undoesWithdrawalDate" to "2024-01-15",
                      "undoesWithdrawalId" to "$withdrawalId",
                  ),
              )
          )

      val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()), orderBy)

      assertJsonEquals(expected, actual)
    }

    @Test
    fun `species projects table returns projects for nonempty batches`() {
      val projectId1 = insertProject(name = "Project 1")
      val projectId2 = insertProject(name = "Project 2")
      val projectId3 = insertProject(name = "Project 3")
      val projectIdWithOtherSpecies = insertProject(name = "Other Species")
      val projectIdWithEmptyBatch = insertProject(name = "Empty Project")

      // Two batches in project 1 to check that projects are distinct
      insertBatch(germinatingQuantity = 1, speciesId = speciesId1, projectId = projectId1)
      insertBatch(germinatingQuantity = 1, speciesId = speciesId1, projectId = projectId1)
      insertBatch(activeGrowthQuantity = 1, speciesId = speciesId1, projectId = projectId2)
      insertBatch(readyQuantity = 1, speciesId = speciesId1, projectId = projectId3)

      insertBatch(readyQuantity = 1, speciesId = speciesId2, projectId = projectIdWithOtherSpecies)
      insertBatch(speciesId = speciesId1, projectId = projectIdWithEmptyBatch)

      val prefix = SearchFieldPrefix(root = searchTables.nurserySpeciesProjects)
      val fields = listOf(prefix.resolve("project_name"))
      val orderBy = listOf(SearchSortField(prefix.resolve("project_name")))

      val expected =
          SearchResults(
              listOf(
                  mapOf("project_name" to "Project 1"),
                  mapOf("project_name" to "Project 2"),
                  mapOf("project_name" to "Project 3"),
              )
          )

      val actual =
          searchService.search(
              prefix,
              fields,
              mapOf(prefix to FieldNode(prefix.resolve("species_id"), listOf("$speciesId1"))),
              orderBy,
          )

      assertJsonEquals(expected, actual)
    }

    @Test
    fun `species projects table does not include batches without projects`() {
      val prefix = SearchFieldPrefix(root = searchTables.species)
      val fields =
          listOf(
                  "id",
                  "nurseryProjects.project_id",
              )
              .map { prefix.resolve(it) }
      val orderBy = listOf(SearchSortField(prefix.resolve("id")))

      val expected =
          SearchResults(
              listOf(
                  mapOf("id" to "$speciesId1"),
                  mapOf("id" to "$speciesId2"),
              )
          )

      val actual =
          searchService.search(
              prefix,
              fields,
              mapOf(
                  prefix to FieldNode(prefix.resolve("organization_id"), listOf("$organizationId"))
              ),
              orderBy,
          )

      assertJsonEquals(expected, actual)
    }
  }

  /**
   * Renders a number using English formatting rules to match the search API's rendering of numeric
   * fields. 1024 becomes "1,024".
   */
  private fun number(value: Int) = numberFormat.format(value)
}
