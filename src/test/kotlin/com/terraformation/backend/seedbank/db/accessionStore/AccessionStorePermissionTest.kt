package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.seedbank.model.AccessionModel
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class AccessionStorePermissionTest : AccessionStoreTest() {
  @Test
  fun `fetchOneById throws exception if user does not have permission`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    assertNotNull(initial, "Should have created accession successfully")

    every { user.canReadAccession(any()) } returns false

    assertThrows<AccessionNotFoundException> { store.fetchOneById(initial.id!!) }
  }

  @Test
  fun `update does not write to database if user does not have permission`() {
    every { user.canUpdateAccession(any()) } returns false
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<AccessDeniedException> { store.update(initial.copy(numberOfTrees = 1)) }

    val afterUpdate = store.fetchOneById(initial.id!!)
    assertNotNull(afterUpdate, "Should be able to read accession after updating")
    assertNull(afterUpdate.numberOfTrees, "Update should not have been written")
  }

  @Test
  fun `fetchHistory throws exception if user does not have permission`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    every { user.canReadAccession(any()) } returns false

    assertThrows<AccessionNotFoundException> { store.fetchHistory(initial.id!!) }
  }

  @Test
  fun `delete throws exception if user does not have permission`() {
    every { user.canDeleteAccession(any()) } returns false
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<AccessDeniedException> { store.delete(initial.id!!) }
  }

  @Test
  fun `update ignores received and collected date edits for accessions from web`() {
    val initialCollectedDate = LocalDate.of(2021, 1, 1)
    val initialReceivedDate = LocalDate.of(2021, 1, 2)
    val updatedDate = LocalDate.of(2021, 2, 2)
    val initial =
        store.create(
            AccessionModel(
                collectedDate = initialCollectedDate,
                facilityId = facilityId,
                receivedDate = initialReceivedDate))
    val desired = initial.copy(collectedDate = updatedDate, receivedDate = updatedDate)

    store.update(desired)

    val actual = store.fetchOneById(initial.id!!)

    assertEquals(desired, actual)
  }

  @Test
  fun `rejects count active accessions by facility when user cannot read facility`() {
    every { user.canReadFacility(any()) } returns false

    assertThrows<FacilityNotFoundException> { store.countActive(facilityId) }
  }

  @Test
  fun `rejects count active accessions by organization when user cannot read organization`() {
    every { user.canReadOrganization(any()) } returns false

    assertThrows<OrganizationNotFoundException> { store.countActive(organizationId) }
  }

  @Test
  fun `countByState throws exception when no permission to read facility`() {
    every { user.canReadFacility(facilityId) } returns false

    assertThrows<FacilityNotFoundException> { store.countByState(facilityId) }
  }

  @Test
  fun `countByState throws exception when no permission to read organization`() {
    every { user.canReadOrganization(organizationId) } returns false

    assertThrows<OrganizationNotFoundException> { store.countByState(organizationId) }
  }

  @Test
  fun `getSummaryStatistics throws exception when no permission to read facility`() {
    every { user.canReadFacility(facilityId) } returns false

    assertThrows<FacilityNotFoundException> { store.getSummaryStatistics(facilityId) }
  }

  @Test
  fun `getSummaryStatistics throws exception when no permission to read organization`() {
    every { user.canReadOrganization(organizationId) } returns false

    assertThrows<OrganizationNotFoundException> { store.getSummaryStatistics(organizationId) }
  }
}
