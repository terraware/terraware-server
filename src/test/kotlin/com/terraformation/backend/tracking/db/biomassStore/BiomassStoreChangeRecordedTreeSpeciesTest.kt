package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.db.RecordedTreeNotFoundException
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEventValues
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreChangeRecordedTreeSpeciesTest : BaseBiomassStoreTest() {
  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var observationId: ObservationId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUpObservation() {
    insertStratum()
    insertSubstratum()
    monitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    speciesId = insertSpecies(scientificName = "Old tree")
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `changes every trunk, scopes the change, and publishes exact events`() {
    val oldBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val newSpeciesId = insertSpecies(scientificName = "New tree")
    val newTreeId = insertTree(oldBiomassSpeciesId, treeGrowthForm = TreeGrowthForm.Trunk)
    val secondTrunkId =
        insertTree(oldBiomassSpeciesId, trunkNumber = 2, treeGrowthForm = TreeGrowthForm.Trunk)
    val foreignSpeciesId = insertSpecies(organizationId = insertOrganization())
    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    val otherObservationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    val treesBefore = dslContext.fetch(RECORDED_TREES)
    val speciesBefore = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)

    assertThrows<RecordedTreeNotFoundException> {
      changeSpecies(newTreeId, newSpeciesId, plot = otherMonitoringPlotId)
    }
    assertThrows<SpeciesNotFoundException> {
      changeSpecies(newTreeId, foreignSpeciesId)
    }
    assertThrows<RecordedTreeNotFoundException> {
      changeSpecies(newTreeId, newSpeciesId, observation = otherObservationId)
    }
    assertEquals(treesBefore, dslContext.fetch(RECORDED_TREES))
    assertEquals(speciesBefore, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES))
    eventPublisher.assertNoEventsPublished()

    changeSpecies(newTreeId, newSpeciesId)

    val newBiomassSpecies =
        dslContext
            .selectFrom(OBSERVATION_BIOMASS_SPECIES)
            .where(OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(newSpeciesId))
            .fetchSingle()
    assertEquals(
        mapOf(newTreeId to newBiomassSpecies.id, secondTrunkId to newBiomassSpecies.id),
        dslContext.fetch(RECORDED_TREES).associate { it.id to it.biomassSpeciesId },
    )
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)

    val oldSpeciesValues =
        RecordedTreeUpdatedEventValues(
            biomassSpeciesId = oldBiomassSpeciesId,
            speciesId = speciesId,
            speciesName = "Old tree",
        )
    val speciesCreatedEvent =
        BiomassSpeciesCreatedEvent(
            biomassSpeciesId = newBiomassSpecies.id!!,
            commonName = null,
            isInvasive = false,
            isThreatened = false,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            scientificName = null,
            speciesId = newSpeciesId,
            speciesName = "New tree",
        )
    val updateEvent =
        RecordedTreeUpdatedEvent(
            changedFrom = oldSpeciesValues,
            changedTo =
                oldSpeciesValues.copy(
                    biomassSpeciesId = newBiomassSpecies.id,
                    speciesId = newSpeciesId,
                    speciesName = "New tree",
                ),
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            recordedTreeId = newTreeId,
        )
    val speciesDeletedEvent =
        BiomassSpeciesDeletedEvent(
            biomassSpeciesId = oldBiomassSpeciesId,
            commonName = null,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            scientificName = null,
            speciesId = speciesId,
            speciesName = "Old tree",
        )
    eventPublisher.assertExactEventsPublished(
        listOf(
            speciesCreatedEvent,
            updateEvent,
            updateEvent.copy(recordedTreeId = secondTrunkId),
            speciesDeletedEvent,
        )
    )
    changeSpecies(newTreeId, null, "Other tree")
    changeSpecies(secondTrunkId, null, "Other tree")
    val otherBiomassSpecies = dslContext.fetchSingle(OBSERVATION_BIOMASS_SPECIES)
    assertEquals("Other tree", otherBiomassSpecies.scientificName)

    insertObservationBiomassQuadratDetails(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
    )
    insertObservationBiomassQuadratSpecies(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
        biomassSpeciesId = otherBiomassSpecies.id,
    )
    changeSpecies(newTreeId, newSpeciesId)
    changeSpecies(secondTrunkId, newSpeciesId)
    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
  }

  @Test
  fun `requires quantity permission`() {
    every { user.canUpdateObservationQuantities(observationId) } returns false

    assertThrows<AccessDeniedException> { changeSpecies(RecordedTreeId(1), speciesId) }
  }

  private fun changeSpecies(
      treeId: RecordedTreeId,
      speciesId: SpeciesId?,
      speciesName: String? = null,
      observation: ObservationId = this.observationId,
      plot: MonitoringPlotId = this.monitoringPlotId,
  ) = store.changeRecordedTreeSpecies(observation, plot, treeId, speciesId, speciesName)

  private fun insertTree(
      biomassSpeciesId: BiomassSpeciesId,
      treeNumber: Int = 1,
      trunkNumber: Int = 1,
      treeGrowthForm: TreeGrowthForm = TreeGrowthForm.Tree,
  ): RecordedTreeId =
      insertRecordedTree(
          biomassSpeciesId = biomassSpeciesId,
          diameterAtBreastHeightCm = BigDecimal.ONE,
          pointOfMeasurementM = BigDecimal("1.3"),
          treeGrowthForm = treeGrowthForm,
          treeNumber = treeNumber,
          trunkNumber = trunkNumber,
      )
}
