package com.terraformation.backend.seedbank.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DataSource
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.ViabilityTestType
import com.terraformation.backend.db.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import com.terraformation.backend.seedbank.db.StorageLocationStore
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ValuesControllerTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock: Clock = mockk()
  private val controller: ValuesController by lazy {
    ValuesController(
        SearchTables(clock), StorageLocationStore(dslContext), SearchService(dslContext))
  }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH

    insertSiteData()

    insertOrganizationUser(user.userId, organizationId)
  }

  @Test
  fun `listAllFieldValues returns values from the requested organization`() {
    val otherOrganizationId = OrganizationId(2)
    val otherFacilityId = FacilityId(2)

    insertOrganization(otherOrganizationId)
    insertOrganizationUser(user.userId, otherOrganizationId)
    insertFacility(otherFacilityId, otherOrganizationId)

    every { user.facilityRoles } returns
        mapOf(facilityId to Role.MANAGER, otherFacilityId to Role.MANAGER)

    val accessionsRow =
        AccessionsRow(
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            dataSourceId = DataSource.Web,
            facilityId = facilityId,
            isManualState = true,
            modifiedBy = user.userId,
            modifiedTime = Instant.EPOCH,
            stateId = AccessionState.Cleaning,
        )

    val org1Accession = accessionsRow.copy(facilityId = facilityId)
    val org2Accession = accessionsRow.copy(facilityId = otherFacilityId)
    accessionsDao.insert(org1Accession, org2Accession)

    accessionCollectorsDao.insert(
        AccessionCollectorsRow(org1Accession.id, 0, "o1c0"),
        AccessionCollectorsRow(org1Accession.id, 1, "o1c1"),
        AccessionCollectorsRow(org2Accession.id, 0, "o2c0"),
    )

    val viabilityTestsRow =
        ViabilityTestsRow(
            testType = ViabilityTestType.Lab,
            remainingQuantity = BigDecimal.ONE,
            remainingUnitsId = SeedQuantityUnits.Seeds)
    val org1Test = viabilityTestsRow.copy(accessionId = org1Accession.id, notes = "o1")
    val org2Test = viabilityTestsRow.copy(accessionId = org2Accession.id, notes = "o2")
    viabilityTestsDao.insert(org1Test, org2Test)

    val request =
        ListAllFieldValuesRequestPayload(
            null, listOf("collectors_name", "viabilityTests_notes"), organizationId)

    val expected =
        ListAllFieldValuesResponsePayload(
            mapOf(
                "collectors_name" to AllFieldValuesPayload(listOf(null, "o1c0", "o1c1"), false),
                "viabilityTests_notes" to AllFieldValuesPayload(listOf(null, "o1"), false)))

    val actual = controller.listAllFieldValues(request)
    assertEquals(expected, actual)
  }
}
