package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.seedbank.model.AccessionModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

internal class AccessionStoreManualStateTest : AccessionStoreTest() {
  @Test
  fun `update allows state to be modified if isManualState flag is set`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId, isManualState = true, state = AccessionState.Processing))

    val updated = store.updateAndFetch(initial.copy(state = AccessionState.Drying))

    assertEquals(AccessionState.Drying, updated.state)
  }

  @Test
  fun `update allows setting isManualState on existing non-manual-state accession`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    val updated =
        store.updateAndFetch(initial.copy(isManualState = true, state = AccessionState.Drying))

    assertEquals(AccessionState.Drying, updated.state)
  }

  @Test
  fun `update computes new state if isManualState is cleared on existing manual-state accession`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId,
                isManualState = true,
                state = AccessionState.AwaitingProcessing))

    val updated = store.updateAndFetch(initial.copy(isManualState = false))

    assertEquals(AccessionState.Pending, updated.state)
  }

  @Test
  fun `update allows state to be changed from Awaiting Check-In if isManualState flag is set`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId,
                isManualState = true,
                state = AccessionState.AwaitingCheckIn))

    val updated = store.updateAndFetch(initial.copy(state = AccessionState.Drying))

    assertEquals(AccessionState.Drying, updated.state)

    // Remove this once we don't need v1 interoperability and checkedInTime goes away.
    assertNotNull(
        updated.checkedInTime, "Accession should be counted as checked in when state is changed")
  }

  @Test
  fun `update without isManualState uses caller-supplied species name`() {
    val oldSpeciesId = SpeciesId(1)
    val newSpeciesId = SpeciesId(2)
    val oldSpeciesName = "Old species"
    val newSpeciesName = "New species"
    insertSpecies(oldSpeciesId, oldSpeciesName)
    insertSpecies(newSpeciesId, newSpeciesName)

    val initial = store.create(AccessionModel(facilityId = facilityId, species = oldSpeciesName))
    val updated =
        store.updateAndFetch(initial.copy(species = newSpeciesName, speciesId = oldSpeciesId))

    assertEquals(newSpeciesId, updated.speciesId, "Species ID")
    assertEquals(newSpeciesName, updated.species, "Species scientific name")
  }

  @Test
  fun `update with isManualState uses caller-supplied species ID`() {
    val oldSpeciesId = SpeciesId(1)
    val newSpeciesId = SpeciesId(2)
    insertSpecies(oldSpeciesId, "Old species")
    insertSpecies(newSpeciesId, "New species")

    val initial =
        store.create(
            AccessionModel(facilityId = facilityId, isManualState = true, speciesId = oldSpeciesId))
    val updated =
        store.updateAndFetch(initial.copy(species = "Implicit species", speciesId = newSpeciesId))

    assertEquals(newSpeciesId, updated.speciesId, "Species ID")
    assertEquals("New species", updated.species, "Species scientific name")
  }
}
