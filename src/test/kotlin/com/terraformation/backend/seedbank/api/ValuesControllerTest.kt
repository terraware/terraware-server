package com.terraformation.backend.seedbank.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ValuesControllerTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val controller: ValuesController by lazy {
    ValuesController(SearchTables(clock), SearchService(dslContext))
  }

  @BeforeEach
  fun setUp() {
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
        mapOf(facilityId to Role.Manager, otherFacilityId to Role.Manager)

    val org1AccessionId = insertAccession()
    val org2AccessionId = insertAccession(facilityId = otherFacilityId)

    accessionCollectorsDao.insert(
        AccessionCollectorsRow(org1AccessionId, 0, "o1c0"),
        AccessionCollectorsRow(org1AccessionId, 1, "o1c1"),
        AccessionCollectorsRow(org2AccessionId, 0, "o2c0"),
    )

    val viabilityTestsRow = ViabilityTestsRow(testType = ViabilityTestType.Lab)
    val org1Test = viabilityTestsRow.copy(accessionId = org1AccessionId, notes = "o1")
    val org2Test = viabilityTestsRow.copy(accessionId = org2AccessionId, notes = "o2")
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
