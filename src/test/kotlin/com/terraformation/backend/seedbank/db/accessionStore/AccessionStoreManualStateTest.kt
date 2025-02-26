package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AccessionStoreManualStateTest : AccessionStoreTest() {
  @Test
  fun `update allows state to be modified if isManualState flag is set`() {
    val initial = store.create(accessionModel(state = AccessionState.Processing))

    val updated = store.updateAndFetch(initial.copy(state = AccessionState.Drying))

    assertEquals(AccessionState.Drying, updated.state)
  }

  @Test
  fun `update allows state to be changed from Awaiting Check-In if isManualState flag is set`() {
    val initial = store.create(accessionModel(state = AccessionState.AwaitingCheckIn))

    val updated = store.updateAndFetch(initial.copy(state = AccessionState.Drying))

    assertEquals(AccessionState.Drying, updated.state)
  }

  @Test
  fun `update with isManualState uses caller-supplied species ID`() {
    val oldSpeciesId = insertSpecies(scientificName = "Old species")
    val newSpeciesId = insertSpecies(scientificName = "New species")

    val initial = store.create(accessionModel(speciesId = oldSpeciesId))
    val updated =
        store.updateAndFetch(initial.copy(species = "Implicit species", speciesId = newSpeciesId))

    assertEquals(newSpeciesId, updated.speciesId, "Species ID")
    assertEquals("New species", updated.species, "Species scientific name")
  }
}
