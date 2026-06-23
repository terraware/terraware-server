package com.terraformation.backend.statistics.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
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
}
