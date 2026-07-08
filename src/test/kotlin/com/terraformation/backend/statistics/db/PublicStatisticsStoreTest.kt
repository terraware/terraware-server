package com.terraformation.backend.statistics.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
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
  fun `counts organizations excluding ones tagged as internal or testing`() {
    insertOrganization()
    insertOrganization()
    val internalOrganizationId = insertOrganization()
    val testingOrganizationId = insertOrganization()
    insertOrganizationInternalTag(internalOrganizationId, InternalTagIds.Internal)
    insertOrganizationInternalTag(testingOrganizationId, InternalTagIds.Testing)

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
  fun `sums distinct countries, excluding internal organizations`() {
    insertOrganization(countryCode = "US")
    val kenyaOrganizationId = insertOrganization(countryCode = "KE")

    // A project in a new country, plus one that duplicates the org's country.
    insertProject(organizationId = kenyaOrganizationId, countryCode = "BR")
    insertProject(organizationId = kenyaOrganizationId, countryCode = "KE")

    insertOrganization(countryCode = null)

    insertOrganization(countryCode = "MX")
    insertOrganizationInternalTag(tagId = InternalTagIds.Internal)

    insertOrganization(countryCode = "SE")
    insertOrganizationInternalTag(tagId = InternalTagIds.Testing)

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
  fun `sums planting site area excluding internal organizations`() {
    val organizationId = insertOrganization()
    insertPlantingSite(organizationId = organizationId, areaHa = BigDecimal(10))
    insertPlantingSite(organizationId = organizationId, areaHa = BigDecimal(5))

    val internalOrganizationId = insertOrganization()
    insertOrganizationInternalTag(internalOrganizationId, InternalTagIds.Internal)
    insertPlantingSite(organizationId = internalOrganizationId, areaHa = BigDecimal(100))

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
  fun `excludes seeds from internal organizations`() {
    val internalOrgId = insertOrganization()
    insertOrganizationInternalTag(internalOrgId, InternalTagIds.Internal)
    val facilityId = insertFacility(organizationId = internalOrgId, type = FacilityType.SeedBank)
    insertAccession(
        row = AccessionsRow(estSeedCount = 100),
        facilityId = facilityId,
        stateId = AccessionState.InStorage,
    )

    assertEquals(0L, store.fetchStatistics().totalSeedsInStorage)
  }

  @Test
  fun `sums all seedling quantities excluding internal organizations`() {
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

    val internalOrgId = insertOrganization()
    insertOrganizationInternalTag(internalOrgId, InternalTagIds.Internal)
    insertFacility(organizationId = internalOrgId, type = FacilityType.Nursery)
    insertSpecies(organizationId = internalOrgId)
    insertBatch(
        organizationId = internalOrgId,
        germinatingQuantity = 100,
        activeGrowthQuantity = 100,
        readyQuantity = 100,
        hardeningOffQuantity = 100,
    )

    assertEquals(65L, store.fetchStatistics().totalSeedlingsInNurseries)
  }

  @Test
  fun `counts plantings excluding internal organizations`() {
    val organizationId = insertOrganization()
    insertSpecies()
    val facilityId = insertFacility(organizationId = organizationId, type = FacilityType.Nursery)
    val plantingSiteId = insertPlantingSite(organizationId = organizationId)
    val withdrawalId1 = insertNurseryWithdrawal(facilityId = facilityId)
    insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId1)
    insertPlanting(numPlants = 10)

    val internalOrgId = insertOrganization()
    insertOrganizationInternalTag(internalOrgId, InternalTagIds.Internal)
    val internalFacilityId =
        insertFacility(organizationId = internalOrgId, type = FacilityType.Nursery)
    val internalPlantingSiteId = insertPlantingSite(organizationId = internalOrgId)
    val internalWithdrawalId = insertNurseryWithdrawal(facilityId = internalFacilityId)
    insertDelivery(plantingSiteId = internalPlantingSiteId, withdrawalId = internalWithdrawalId)
    insertPlanting(numPlants = 5)

    assertEquals(10, store.fetchStatistics().totalPlantings)
  }
}
