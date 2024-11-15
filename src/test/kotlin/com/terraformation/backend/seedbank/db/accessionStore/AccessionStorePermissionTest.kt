package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.Role
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class AccessionStorePermissionTest : AccessionStoreTest() {
  @Test
  fun `fetchOneById throws exception if user does not have permission`() {
    val initial = store.create(accessionModel())
    assertNotNull(initial, "Should have created accession successfully")

    deleteOrganizationUser()

    assertThrows<AccessionNotFoundException> { store.fetchOneById(initial.id!!) }
  }

  @Test
  fun `fetchHistory throws exception if user does not have permission`() {
    val initial = store.create(accessionModel())

    deleteOrganizationUser()

    assertThrows<AccessionNotFoundException> { store.fetchHistory(initial.id!!) }
  }

  @Test
  fun `delete throws exception if user does not have permission`() {
    val initial = store.create(accessionModel())

    insertOrganizationUser(role = Role.Contributor)

    assertThrows<AccessDeniedException> { store.delete(initial.id!!) }
  }

  @Test
  fun `countByState throws exception when no permission to read facility`() {
    deleteOrganizationUser()

    assertThrows<FacilityNotFoundException> { store.countByState(facilityId) }
  }

  @Test
  fun `countByState throws exception when no permission to read organization`() {
    deleteOrganizationUser()

    assertThrows<OrganizationNotFoundException> { store.countByState(organizationId) }
  }

  @Test
  fun `getSummaryStatistics throws exception when no permission to read facility`() {
    deleteOrganizationUser()

    assertThrows<FacilityNotFoundException> { store.getSummaryStatistics(facilityId) }
  }

  @Test
  fun `getSummaryStatistics throws exception when no permission to read organization`() {
    deleteOrganizationUser()

    assertThrows<OrganizationNotFoundException> { store.getSummaryStatistics(organizationId) }
  }
}
