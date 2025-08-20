package com.terraformation.backend.seedbank.search

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.model.AccessionSummaryStatistics
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

internal class AccessionServiceSearchSummaryTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val accessionStore: AccessionStore by lazy {
    AccessionStore(
        dslContext,
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        clock,
        TestEventPublisher(),
        mockk(),
        mockk(),
    )
  }
  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables: SearchTables by lazy { SearchTables(clock) }

  private val service: AccessionService by lazy {
    AccessionService(
        accessionStore,
        mockk(),
        dslContext,
        mockk(),
        mockk(),
        searchService,
        searchTables,
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser()
    insertFacility()

    every { user.facilityRoles } returns mapOf(inserted.facilityId to Role.Contributor)
  }

  @Test
  fun `returns exact-match statistics for exact-or-fuzzy searches if there are exact matches`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    insertAccession(
        AccessionsRow(
            number = "22-1-001",
            remainingQuantity = BigDecimal.TEN,
            remainingUnitsId = SeedQuantityUnits.Seeds,
            speciesId = speciesId1,
        )
    )
    insertAccession(
        AccessionsRow(
            number = "22-1-002",
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Kilograms,
            speciesId = speciesId2,
        )
    )

    val accessionNumberField =
        SearchFieldPrefix(root = searchTables.accessions).resolve("accessionNumber")
    val criteriaWithExactMatch =
        FieldNode(accessionNumberField, listOf("22-1-001"), SearchFilterType.ExactOrFuzzy)
    val criteriaWithoutExactMatch =
        FieldNode(accessionNumberField, listOf("22-1-000"), SearchFilterType.ExactOrFuzzy)

    assertAll(
        {
          assertEquals(
              AccessionSummaryStatistics(
                  accessions = 1,
                  species = 1,
                  subtotalBySeedCount = 10L,
                  subtotalByWeightEstimate = 0L,
                  totalSeedsRemaining = 10L,
                  seedsWithdrawn = 0L,
                  unknownQuantityAccessions = 0,
              ),
              service.getSearchSummaryStatistics(criteriaWithExactMatch),
              "Statistics for fuzzy search that has an exact match",
          )
        },
        {
          assertEquals(
              AccessionSummaryStatistics(
                  accessions = 2,
                  species = 2,
                  subtotalBySeedCount = 10L,
                  subtotalByWeightEstimate = 0L,
                  totalSeedsRemaining = 10L,
                  seedsWithdrawn = 0L,
                  unknownQuantityAccessions = 1,
              ),
              service.getSearchSummaryStatistics(criteriaWithoutExactMatch),
              "Statistics for fuzzy search that does not have an exact match",
          )
        },
    )
  }
}
