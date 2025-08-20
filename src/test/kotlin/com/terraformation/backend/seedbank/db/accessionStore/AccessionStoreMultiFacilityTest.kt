package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreMultiFacilityTest : AccessionStoreTest() {
  private lateinit var otherFacilityId: FacilityId

  @BeforeEach
  fun createOtherFacility() {
    otherFacilityId = insertFacility()
  }

  @Test
  fun `can create accessions with different facility IDs`() {
    val accessionInMainFacility = store.create(accessionModel())
    val accessionInOtherFacility = store.create(accessionModel(facilityId = otherFacilityId))

    assertNotEquals(
        accessionInMainFacility.accessionNumber,
        accessionInOtherFacility.accessionNumber,
        "Accession number",
    )

    assertEquals(accessionInMainFacility.facilityId, facilityId, "Accession in main facility")
    assertEquals(
        accessionInOtherFacility.facilityId,
        otherFacilityId,
        "Accession in other facility",
    )
  }

  @Test
  fun `update writes new facility id if it belongs to the same organization as previous facility`() {
    val anotherFacilityId = insertFacility()

    val initial = store.create(accessionModel())

    store.update(initial.copy(facilityId = anotherFacilityId))

    val afterUpdate = store.fetchOneById(initial.id!!)
    assertNotNull(afterUpdate, "Should be able to read accession after updating")
    assertEquals(
        afterUpdate.facilityId,
        anotherFacilityId,
        "Update should have updated facility id",
    )
  }

  @Test
  fun `update does not write to database if facility id to update does not belong to same organization as previous facility`() {
    val initialFacilityId = inserted.facilityId
    insertOrganization()
    insertOrganizationUser()
    val facilityIdInAnotherOrg = insertFacility()

    val initial = store.create(accessionModel(facilityId = initialFacilityId))

    assertThrows<FacilityNotFoundException> {
      store.update(initial.copy(facilityId = facilityIdInAnotherOrg))
    }

    val afterUpdate = store.fetchOneById(initial.id!!)
    assertNotNull(afterUpdate, "Should be able to read accession after updating")
    assertEquals(initialFacilityId, afterUpdate.facilityId, "Update should not updated facility id")
  }
}
