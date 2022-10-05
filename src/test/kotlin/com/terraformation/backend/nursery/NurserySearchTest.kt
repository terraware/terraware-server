package com.terraformation.backend.nursery

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
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
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class NurserySearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

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

  @Test
  fun `summary tables return correct totals`() {
    val organizationId2 = OrganizationId(2)
    val speciesId1 = SpeciesId(1)
    val speciesId2 = SpeciesId(2)
    val org2SpeciesId = SpeciesId(3)
    val facilityId2 = FacilityId(200)
    val org2FacilityId = FacilityId(300)

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
        facilityId = facilityId,
        germinatingQuantity = 1,
        notReadyQuantity = 2,
        readyQuantity = 4,
        speciesId = speciesId1)
    insertBatch(
        facilityId = facilityId,
        germinatingQuantity = 8,
        notReadyQuantity = 16,
        readyQuantity = 32,
        speciesId = speciesId1,
    )
    insertBatch(
        facilityId = facilityId2,
        germinatingQuantity = 64,
        notReadyQuantity = 128,
        readyQuantity = 256,
        speciesId = speciesId1,
    )
    insertBatch(
        facilityId = facilityId,
        germinatingQuantity = 512,
        notReadyQuantity = 1024,
        readyQuantity = 2048,
        speciesId = speciesId2,
    )
    insertBatch(
        organizationId = organizationId2,
        facilityId = org2FacilityId,
        germinatingQuantity = 4096,
        notReadyQuantity = 8192,
        readyQuantity = 16384,
        speciesId = org2SpeciesId,
    )

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
}
