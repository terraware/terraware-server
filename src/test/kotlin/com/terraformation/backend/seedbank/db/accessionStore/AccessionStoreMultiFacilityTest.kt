package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.seedbank.model.AccessionModel
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreMultiFacilityTest : AccessionStoreTest() {
  private val otherFacilityId = FacilityId(500)

  @BeforeEach
  fun createOtherFacility() {
    insertFacility(otherFacilityId)
  }

  @Test
  fun `can create accessions with different facility IDs`() {
    val accessionInMainFacility = store.create(AccessionModel(facilityId = facilityId))
    val accessionInOtherFacility = store.create(AccessionModel(facilityId = otherFacilityId))

    assertNotEquals(
        accessionInMainFacility.accessionNumber,
        accessionInOtherFacility.accessionNumber,
        "Accession number")

    assertEquals(accessionInMainFacility.facilityId, facilityId, "Accession in main facility")
    assertEquals(
        accessionInOtherFacility.facilityId, otherFacilityId, "Accession in other facility")
  }

  @Test
  fun `update writes new facility id if it belongs to the same organization as previous facility`() {
    val anotherFacilityId = FacilityId(5000)
    insertFacility(anotherFacilityId)

    every { user.canUpdateAccession(any()) } returns true
    val initial = store.create(AccessionModel(facilityId = facilityId))

    store.update(initial.copy(facilityId = anotherFacilityId))

    val afterUpdate = store.fetchOneById(initial.id!!)
    assertNotNull(afterUpdate, "Should be able to read accession after updating")
    assertEquals(
        afterUpdate.facilityId, anotherFacilityId, "Update should have updated facility id")
  }

  @Test
  fun `update does not write to database if facility id to update does not belong to same organization as previous facility`() {
    val anotherOrgId = OrganizationId(5)
    val facilityIdInAnotherOrg = FacilityId(5000)
    insertOrganization(anotherOrgId, "dev-2")
    insertFacility(facilityIdInAnotherOrg, anotherOrgId)

    every { user.canUpdateAccession(any()) } returns true
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<FacilityNotFoundException> {
      store.update(initial.copy(facilityId = facilityIdInAnotherOrg))
    }

    val afterUpdate = store.fetchOneById(initial.id!!)
    assertNotNull(afterUpdate, "Should be able to read accession after updating")
    assertEquals(afterUpdate.facilityId, facilityId, "Update should not updated facility id")
  }
}
