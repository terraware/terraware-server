package com.terraformation.backend.seedbank.db.accessionStore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

internal class AccessionStoreBagTest : AccessionStoreTest() {
  @Test
  fun `bag numbers are not shared between accessions`() {
    val payload = accessionModel(bagNumbers = setOf("bag 1", "bag 2"))
    val accessionId1 = store.create(payload).id!!
    val accessionId2 = store.create(payload).id!!

    val initialBags = bagsDao.fetchByAccessionId(accessionId1).toSet()
    val secondBags = bagsDao.fetchByAccessionId(accessionId2).toSet()

    assertNotEquals(initialBags, secondBags)
  }

  @Test
  fun `bags are inserted and deleted as needed`() {
    val initial = store.create(accessionModel(bagNumbers = setOf("bag 1", "bag 2")))
    val accessionId = initial.id!!
    val initialBags = bagsDao.fetchByAccessionId(accessionId)

    assertEquals(
        setOf("bag 1", "bag 2"),
        initialBags.map { it.bagNumber }.toSet(),
        "Initial bag numbers",
    )

    val bag1Id = initialBags.first { it.bagNumber == "bag 1" }.id!!
    val bag2Id = initialBags.first { it.bagNumber == "bag 2" }.id!!

    val desired = initial.copy(bagNumbers = setOf("bag 2", "bag 3"))

    store.update(desired)

    val updatedBags = bagsDao.fetchByAccessionId(accessionId)
    val bag3Id = updatedBags.first { it.bagNumber == "bag 3" }.id!!

    assertNotEquals(bag1Id, bag3Id, "Should not have reused bag 1 ID")
    assertNotEquals(bag2Id, bag3Id, "Should not have reused bag 2 ID")
    assertEquals(
        emptyList<Any>(),
        updatedBags.filter { it.bagNumber == "bag 1" },
        "Missing bag not deleted",
    )
    assertEquals(
        initialBags.filter { it.bagNumber == "bag 2" },
        updatedBags.filter { it.bagNumber == "bag 2" },
        "Existing bag is not replaced",
    )
  }
}
