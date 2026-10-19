package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceWithdrawalsTest : SearchServiceTest() {
  private val withdrawalsPrefix = SearchFieldPrefix(tables.withdrawals)

  @Test
  fun `returns withdrawal details`() {
    val nurseryId = insertFacility(name = "My Nursery", type = FacilityType.Nursery)
    every { user.facilityRoles } returns
        mapOf(facilityId to Role.Manager, nurseryId to Role.Manager)

    val batchId = insertBatch(batchNumber = "25-2-1-001", facilityId = nurseryId)
    val withdrawerId = insertUser(firstName = "Withdrawing", lastName = "User")
    insertOrganizationUser(withdrawerId, organizationId)

    val viabilityTestId = insertViabilityTest(accessionId = accessionId1)

    val countWithdrawalId =
        insertSeedbankWithdrawal(
            accessionId = accessionId1,
            batchId = batchId,
            estimatedWeightQuantity = BigDecimal(12),
            estimatedWeightUnitsId = SeedQuantityUnits.Grams,
            purpose = WithdrawalPurpose.Nursery,
            withdrawnBy = withdrawerId,
            withdrawnQuantity = BigDecimal(100),
            withdrawnUnitsId = SeedQuantityUnits.Seeds,
        )
    val weightWithdrawalId =
        insertSeedbankWithdrawal(
            accessionId = accessionId1,
            batchId = null,
            estimatedCount = 50,
            purpose = WithdrawalPurpose.ViabilityTesting,
            viabilityTestId = viabilityTestId,
            withdrawnBy = withdrawerId,
            withdrawnGrams = BigDecimal(5),
            withdrawnQuantity = BigDecimal(5),
            withdrawnUnitsId = SeedQuantityUnits.Grams,
        )

    val fields =
        listOf(
                "id",
                "estimatedCount",
                "estimatedWeightQuantity",
                "estimatedWeightUnits",
                "viabilityTestId",
                "withdrawnBy.firstName",
                "withdrawnBy.lastName",
                "batch.batchNumber",
                "batch.facility.name",
            )
            .map { withdrawalsPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$countWithdrawalId",
                    "estimatedWeightQuantity" to "12",
                    "estimatedWeightUnits" to "Grams",
                    "withdrawnBy" to mapOf("firstName" to "Withdrawing", "lastName" to "User"),
                    "batch" to
                        mapOf(
                            "batchNumber" to "25-2-1-001",
                            "facility" to mapOf("name" to "My Nursery"),
                        ),
                ),
                mapOf(
                    "id" to "$weightWithdrawalId",
                    "estimatedCount" to "50",
                    "viabilityTestId" to "$viabilityTestId",
                    "withdrawnBy" to mapOf("firstName" to "Withdrawing", "lastName" to "User"),
                ),
            )
        )

    val actual =
        searchService.search(
            withdrawalsPrefix,
            fields,
            mapOf(withdrawalsPrefix to NoConditionNode()),
        )

    assertEquals(expected, actual)
  }
}
