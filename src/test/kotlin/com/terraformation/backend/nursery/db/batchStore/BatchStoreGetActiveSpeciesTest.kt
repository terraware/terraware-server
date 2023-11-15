package com.terraformation.backend.nursery.db.batchStore

import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreGetActiveSpeciesTest : BatchStoreTest() {
  @Test
  fun `does not include species with no current inventory`() {
    every { user.canReadFacility(facilityId) } returns true

    insertBatch(speciesId = speciesId, germinatingQuantity = 1)
    val speciesId2 = insertSpecies()
    insertBatch(speciesId = speciesId2, notReadyQuantity = 1)
    val speciesId3 = insertSpecies()
    insertBatch(speciesId = speciesId3, readyQuantity = 1)

    val expected = speciesDao.findAll().sortedBy { it.id!!.value }

    val speciesId4 = insertSpecies()
    insertBatch(speciesId = speciesId4)

    val actual = store.getActiveSpecies(facilityId)

    assertEquals(expected, actual)
  }
}
