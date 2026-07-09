package com.terraformation.backend.statistics.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.statistics.PublicStatisticsModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class PublicStatisticsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: PublicStatisticsStore by lazy { PublicStatisticsStore(dslContext) }

  @Test
  fun `returns zeroes when there is no data`() {
    val statistics = store.fetchStatistics()

    assertEquals(
        PublicStatisticsModel(
            totalOrganizations = 0,
            totalCountries = 0,
            totalAreaUnderRestorationHa = BigDecimal.ZERO,
            totalSeedsInStorage = 0L,
            totalSeedlingsInNurseries = 0L,
            totalPlantings = 0,
        ),
        statistics,
        "Should return zeros",
    )
  }

  @Test
  fun `excludes organizations tagged as testing`() {
    insertOrganization()
    val testingOrganizationId = insertOrganization()
    insertOrganizationInternalTag(testingOrganizationId, InternalTagIds.Testing)

    assertEquals(1, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `excludes organizations with a terraformation owner`() {
    insertOrganization()
    val terraformationOrganizationId = insertOrganization()
    addTerraformationOwner(terraformationOrganizationId)

    assertEquals(1, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `matches terraformation owner email case-insensitively`() {
    insertOrganization()
    val terraformationOrganizationId = insertOrganization()
    val userId = insertUser(email = "Owner@Terraformation.COM")
    insertOrganizationUser(
        userId = userId,
        organizationId = terraformationOrganizationId,
        role = Role.Owner,
    )

    assertEquals(1, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `excludes orgs that have terraformation owner and are tagged as both internal and testing`() {
    insertOrganization()
    val organizationId = insertOrganization()
    addTerraformationOwner(organizationId)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Testing)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Internal)

    assertEquals(1, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `does not exclude terraformation-owned organizations tagged as internal`() {
    insertOrganization()
    val internalOrganizationId = insertOrganization()
    addTerraformationOwner(internalOrganizationId)
    insertOrganizationInternalTag(internalOrganizationId, InternalTagIds.Internal)

    assertEquals(2, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `does not exclude organizations tagged as internal without a terraformation owner`() {
    insertOrganization()
    val internalOrganizationId = insertOrganization()
    insertOrganizationInternalTag(internalOrganizationId, InternalTagIds.Internal)

    assertEquals(2, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `does not exclude organizations whose terraformation user is not an owner`() {
    insertOrganization()
    val organizationId = insertOrganization()
    val userId = insertUser(email = "admin@terraformation.com")
    insertOrganizationUser(userId = userId, organizationId = organizationId, role = Role.Admin)

    assertEquals(2, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `does not exclude organizations whose owner has a non-terraformation email`() {
    insertOrganization()
    val organizationId = insertOrganization()
    val userId = insertUser(email = "owner@example.com")
    insertOrganizationUser(userId = userId, organizationId = organizationId, role = Role.Owner)

    assertEquals(2, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `does not exclude organizations with other tags`() {
    insertOrganization()
    val reportingOrganizationId = insertOrganization()
    insertOrganizationInternalTag(reportingOrganizationId, InternalTagIds.Reporter)

    assertEquals(2, store.fetchStatistics().totalOrganizations)
  }

  @Test
  fun `sums distinct countries, excluding excluded organizations`() {
    insertOrganization(countryCode = "US")
    val kenyaOrganizationId = insertOrganization(countryCode = "KE")

    // A project in a new country, plus one that duplicates the org's country.
    insertProject(organizationId = kenyaOrganizationId, countryCode = "BR")
    insertProject(organizationId = kenyaOrganizationId, countryCode = "KE")

    insertOrganization(countryCode = null)

    val terraformationOrganizationId = insertOrganization(countryCode = "MX")
    addTerraformationOwner(terraformationOrganizationId)

    val testingOrganizationId = insertOrganization(countryCode = "SE")
    insertOrganizationInternalTag(testingOrganizationId, InternalTagIds.Testing)

    assertEquals(3, store.fetchStatistics().totalCountries)
  }

  @Test
  fun `returns cached result on subsequent calls`() {
    insertOrganization()
    val firstResult = store.fetchStatistics()

    insertOrganization()
    val secondResult = store.fetchStatistics()

    assertEquals(firstResult, secondResult, "Should return cached result")
  }

  @Test
  fun `sums planting site area excluding excluded organizations`() {
    val organizationId = insertOrganization()
    insertPlantingSite(organizationId = organizationId, areaHa = BigDecimal(10))
    insertPlantingSite(organizationId = organizationId, areaHa = BigDecimal(5))

    val excludedOrganizationId = insertOrganization()
    addTerraformationOwner(excludedOrganizationId)
    insertPlantingSite(organizationId = excludedOrganizationId, areaHa = BigDecimal(100))

    assertEquals(
        0,
        BigDecimal(15).compareTo(store.fetchStatistics().totalAreaUnderRestorationHa),
    )
  }

  @Test
  fun `sums seeds in storage using estimated seed count`() {
    val organizationId = insertOrganization()
    val facilityId = insertFacility(organizationId = organizationId, type = FacilityType.SeedBank)
    insertAccession(
        row = AccessionsRow(estSeedCount = 100),
        facilityId = facilityId,
        stateId = AccessionState.InStorage,
    )
    insertAccession(
        row = AccessionsRow(estSeedCount = 50),
        facilityId = facilityId,
        stateId = AccessionState.Drying,
    )

    assertEquals(150L, store.fetchStatistics().totalSeedsInStorage)
  }

  @Test
  fun `excludes seeds in inactive accession states from storage count`() {
    val organizationId = insertOrganization()
    val facilityId = insertFacility(organizationId = organizationId, type = FacilityType.SeedBank)
    for (state in listOf(AccessionState.Withdrawn, AccessionState.UsedUp, AccessionState.Nursery)) {
      insertAccession(
          row = AccessionsRow(estSeedCount = 100),
          facilityId = facilityId,
          stateId = state,
      )
    }

    assertEquals(0L, store.fetchStatistics().totalSeedsInStorage)
  }

  @Test
  fun `excludes seeds from excluded organizations`() {
    val excludedOrgId = insertOrganization()
    addTerraformationOwner(excludedOrgId)
    val facilityId = insertFacility(organizationId = excludedOrgId, type = FacilityType.SeedBank)
    insertAccession(
        row = AccessionsRow(estSeedCount = 100),
        facilityId = facilityId,
        stateId = AccessionState.InStorage,
    )

    assertEquals(0L, store.fetchStatistics().totalSeedsInStorage)
  }

  @Test
  fun `sums all seedling quantities excluding excluded organizations`() {
    val organizationId = insertOrganization()
    insertFacility(organizationId = organizationId, type = FacilityType.Nursery)
    insertSpecies(organizationId = organizationId)
    insertBatch(
        organizationId = organizationId,
        germinatingQuantity = 10,
        activeGrowthQuantity = 20,
        readyQuantity = 30,
        hardeningOffQuantity = 5,
    )

    val excludedOrgId = insertOrganization()
    addTerraformationOwner(excludedOrgId)
    insertFacility(organizationId = excludedOrgId, type = FacilityType.Nursery)
    insertSpecies(organizationId = excludedOrgId)
    insertBatch(
        organizationId = excludedOrgId,
        germinatingQuantity = 100,
        activeGrowthQuantity = 100,
        readyQuantity = 100,
        hardeningOffQuantity = 100,
    )

    assertEquals(65L, store.fetchStatistics().totalSeedlingsInNurseries)
  }

  @Test
  fun `counts plantings excluding excluded organizations`() {
    val organizationId = insertOrganization()
    insertSpecies()
    val facilityId = insertFacility(organizationId = organizationId, type = FacilityType.Nursery)
    val plantingSiteId = insertPlantingSite(organizationId = organizationId)
    val withdrawalId1 = insertNurseryWithdrawal(facilityId = facilityId)
    insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId1)
    insertPlanting(numPlants = 10)

    val excludedOrgId = insertOrganization()
    addTerraformationOwner(excludedOrgId)
    val excludedFacilityId =
        insertFacility(organizationId = excludedOrgId, type = FacilityType.Nursery)
    val excludedPlantingSiteId = insertPlantingSite(organizationId = excludedOrgId)
    val excludedWithdrawalId = insertNurseryWithdrawal(facilityId = excludedFacilityId)
    insertDelivery(plantingSiteId = excludedPlantingSiteId, withdrawalId = excludedWithdrawalId)
    insertPlanting(numPlants = 5)

    assertEquals(10, store.fetchStatistics().totalPlantings)
  }

  /**
   * Marks an organization as excluded from public statistics by giving it an owner whose email
   * address is in the terraformation.com domain.
   */
  private fun addTerraformationOwner(organizationId: OrganizationId) {
    val userId = insertUser()
    insertOrganizationUser(userId = userId, organizationId = organizationId, role = Role.Owner)
  }
}
