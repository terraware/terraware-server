package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.BagId
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AccessionStoreBagTest : AccessionStoreTest() {
  @Test
  fun `bag numbers are not shared between accessions`() {
    val payload = accessionModel(bagNumbers = setOf("bag 1", "bag 2"))
    store.create(payload)
    store.create(payload)

    val initialBags = bagsDao.fetchByAccessionId(AccessionId(1)).toSet()
    val secondBags = bagsDao.fetchByAccessionId(AccessionId(2)).toSet()

    assertNotEquals(initialBags, secondBags)
  }

  @Test
  fun `bags are inserted and deleted as needed`() {
    val initial = store.create(accessionModel(bagNumbers = setOf("bag 1", "bag 2")))
    val initialBags = bagsDao.fetchByAccessionId(AccessionId(1))

    // Insertion order is not defined by the API, so don't assume bag ID 1 is "bag 1".

    assertEquals(setOf(BagId(1), BagId(2)), initialBags.map { it.id }.toSet(), "Initial bag IDs")
    assertEquals(
        setOf("bag 1", "bag 2"), initialBags.map { it.bagNumber }.toSet(), "Initial bag numbers")

    val desired = initial.copy(bagNumbers = setOf("bag 2", "bag 3"))

    store.update(desired)

    val updatedBags = bagsDao.fetchByAccessionId(AccessionId(1))

    assertTrue(BagsRow(BagId(3), AccessionId(1), "bag 3") in updatedBags, "New bag inserted")
    assertTrue(updatedBags.none { it.bagNumber == "bag 1" }, "Missing bag deleted")
    assertEquals(
        initialBags.filter { it.bagNumber == "bag 2" },
        updatedBags.filter { it.bagNumber == "bag 2" },
        "Existing bag is not replaced")
  }
}
