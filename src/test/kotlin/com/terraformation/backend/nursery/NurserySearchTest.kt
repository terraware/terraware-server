package com.terraformation.backend.nursery

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class NurserySearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(BATCHES)

  private val clock: Clock = mockk()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables: SearchTables by lazy { SearchTables(clock) }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    insertUser()
    insertOrganization(organizationId)
    insertOrganizationUser(user.userId, organizationId, Role.MANAGER)
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

    @BeforeEach
    fun insertBatches() {
      insertOrganization(organizationId2)
      insertOrganizationUser(user.userId, organizationId2, Role.CONTRIBUTOR)

      insertFacility(facilityId2, name = "Other Nursery", type = FacilityType.Nursery)
      insertFacility(
          org2FacilityId,
          name = "Other Org Nursery",
          organizationId = organizationId2,
          type = FacilityType.Nursery)

      insertSpecies(speciesId1)
      insertSpecies(speciesId2)
      insertSpecies(org2SpeciesId, organizationId = organizationId2)

      every { user.facilityRoles } returns
          mapOf(
              facilityId to Role.MANAGER,
              facilityId2 to Role.MANAGER,
              org2FacilityId to Role.CONTRIBUTOR)
      every { user.organizationRoles } returns
          mapOf(organizationId to Role.MANAGER, organizationId2 to Role.CONTRIBUTOR)

      insertBatch(
          addedDate = LocalDate.of(2021, 3, 4),
          facilityId = facilityId,
          germinatingQuantity = 1,
          notReadyQuantity = 2,
          readyQuantity = 4,
          speciesId = speciesId1)
      insertBatch(
          addedDate = LocalDate.of(2022, 9, 2),
          facilityId = facilityId,
          germinatingQuantity = 8,
          notReadyQuantity = 16,
          readyQuantity = 32,
          speciesId = speciesId1,
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
                      "germinatingQuantity" to "${1 + 8 + 64}",
                      "notReadyQuantity" to "${2 + 16 + 128}",
                      "readyQuantity" to "${4 + 32 + 256}",
                      "totalQuantity" to "${2 + 4 + 16 + 32 + 128 + 256}",
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "facility_id" to "$facilityId",
                                  "facility_name" to "Nursery",
                                  "germinatingQuantity" to "${1 + 8}",
                                  "notReadyQuantity" to "${2 + 16}",
                                  "readyQuantity" to "${4 + 32}",
                                  "totalQuantity" to "${2 + 4 + 16 + 32}",
                              ),
                              mapOf(
                                  "facility_id" to "$facilityId2",
                                  "facility_name" to "Other Nursery",
                                  "germinatingQuantity" to "64",
                                  "notReadyQuantity" to "128",
                                  "readyQuantity" to "256",
                                  "totalQuantity" to "${128 + 256}",
                              ),
                          )),
                  mapOf(
                      "species_id" to "2",
                      "germinatingQuantity" to "512",
                      "notReadyQuantity" to "1024",
                      "readyQuantity" to "2048",
                      "totalQuantity" to "${1024 + 2048}",
                      "facilityInventories" to
                          listOf(
                              mapOf(
                                  "facility_id" to "$facilityId",
                                  "facility_name" to "Nursery",
                                  "germinatingQuantity" to "512",
                                  "notReadyQuantity" to "1024",
                                  "readyQuantity" to "2048",
                                  "totalQuantity" to "${1024 + 2048}",
                              ),
                          )),
              ),
              null),
          results)
    }

    @Test
    fun `batches table returns correct totals`() {
      val withdrawalId1 = insertWithdrawal()
      val withdrawalId2 = insertWithdrawal()

      insertBatchWithdrawal(
          withdrawalId = withdrawalId1,
          batchId = 1,
          germinatingQuantityWithdrawn = 512,
          notReadyQuantityWithdrawn = 1024,
          readyQuantityWithdrawn = 2048,
      )
      insertBatchWithdrawal(
          withdrawalId = withdrawalId1,
          batchId = 2,
          germinatingQuantityWithdrawn = 4096,
          notReadyQuantityWithdrawn = 8192,
          readyQuantityWithdrawn = 16384,
      )
      insertBatchWithdrawal(
          withdrawalId = withdrawalId2,
          batchId = 2,
          germinatingQuantityWithdrawn = 32768,
          notReadyQuantityWithdrawn = 65536,
          readyQuantityWithdrawn = 131072,
      )
      insertBatchWithdrawal(
          withdrawalId = withdrawalId2,
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
              ),
              FieldNode(prefix.resolve("species_id"), listOf("$speciesId1")))

      assertEquals(
          SearchResults(
              listOf(
                  mapOf(
                      "id" to "1",
                      "batchNumber" to "1",
                      "germinatingQuantity" to "1",
                      "notReadyQuantity" to "2",
                      "readyQuantity" to "4",
                      "totalQuantity" to "${2 + 4}",
                      "totalQuantityWithdrawn" to "${1024 + 2048}",
                      "facility_name" to "Nursery",
                      "addedDate" to "2021-03-04",
                  ),
                  mapOf(
                      "id" to "2",
                      "batchNumber" to "2",
                      "germinatingQuantity" to "8",
                      "notReadyQuantity" to "16",
                      "readyQuantity" to "32",
                      "totalQuantity" to "${16 + 32}",
                      "totalQuantityWithdrawn" to "${8192 + 16384 + 65536 + 131072}",
                      "facility_name" to "Nursery",
                      "addedDate" to "2022-09-02",
                  ),
                  mapOf(
                      "id" to "3",
                      "batchNumber" to "3",
                      "germinatingQuantity" to "64",
                      "notReadyQuantity" to "128",
                      "readyQuantity" to "256",
                      "totalQuantity" to "${128 + 256}",
                      "totalQuantityWithdrawn" to "0",
                      "facility_name" to "Other Nursery",
                      "readyByDate" to "2022-10-02",
                      "addedDate" to "2022-09-03",
                  ),
              ),
              null),
          results)
    }
  }
}
