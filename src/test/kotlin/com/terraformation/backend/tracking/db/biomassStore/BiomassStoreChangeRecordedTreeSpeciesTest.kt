package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
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
    speciesId = insertSpecies()
    every { user.canUpdateObservationQuantities(any()) } returns true
  }

  @Test
  fun `changes every trunk, scopes the change, and publishes exact events`() {
    val oldBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val oldName = dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(speciesId))!!
    val newSpeciesId = insertSpecies()
    val newTreeId = insertTree(oldBiomassSpeciesId, treeGrowthForm = TreeGrowthForm.Trunk)
    val secondTrunkId =
        insertTree(oldBiomassSpeciesId, trunkNumber = 2, treeGrowthForm = TreeGrowthForm.Trunk)
    val otherMonitoringPlotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = otherMonitoringPlotId,
        completedBy = user.userId,
    )
    insertObservationBiomassDetails(
        observationId = observationId,
        monitoringPlotId = otherMonitoringPlotId,
    )
    val otherBiomassSpeciesId =
        insertObservationBiomassSpecies(
            observationId = observationId,
            monitoringPlotId = otherMonitoringPlotId,
            speciesId = insertSpecies(),
        )
    val otherTreeId =
        insertRecordedTree(
            biomassSpeciesId = otherBiomassSpeciesId,
            observationId = observationId,
            monitoringPlotId = otherMonitoringPlotId,
            diameterAtBreastHeightCm = BigDecimal.ONE,
            pointOfMeasurementM = BigDecimal("1.3"),
            treeNumber = 1,
            trunkNumber = 1,
        )
    val foreignSpeciesId = insertSpecies(organizationId = insertOrganization())
    val otherObservationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    val treesBefore = dslContext.fetch(RECORDED_TREES)
    val speciesBefore = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)

    assertThrows<RecordedTreeNotFoundException> {
      store.changeRecordedTreeSpecies(
          observationId,
          monitoringPlotId,
          otherTreeId,
          newSpeciesId,
          null,
      )
    }
    assertThrows<SpeciesNotFoundException> {
      store.changeRecordedTreeSpecies(
          observationId,
          monitoringPlotId,
          newTreeId,
          foreignSpeciesId,
          null,
      )
    }
    assertThrows<RecordedTreeNotFoundException> {
      store.changeRecordedTreeSpecies(
          otherObservationId,
          monitoringPlotId,
          newTreeId,
          newSpeciesId,
          null,
      )
    }
    assertEquals(treesBefore, dslContext.fetch(RECORDED_TREES))
    assertEquals(speciesBefore, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES))
    eventPublisher.assertNoEventsPublished()

    store.changeRecordedTreeSpecies(
        observationId,
        monitoringPlotId,
        newTreeId,
        newSpeciesId,
        null,
    )

    val newBiomassSpecies =
        dslContext
            .selectFrom(OBSERVATION_BIOMASS_SPECIES)
            .where(OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(newSpeciesId))
            .fetchSingle()
    val newName = dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(newSpeciesId))!!
    val records = dslContext.fetch(RECORDED_TREES).associate { it.id to it.biomassSpeciesId }
    assertEquals(
        mapOf(
            newTreeId to newBiomassSpecies.id,
            secondTrunkId to newBiomassSpecies.id,
            otherTreeId to otherBiomassSpeciesId,
        ),
        records,
    )
    assertEquals(
        setOf(newBiomassSpecies.id, otherBiomassSpeciesId),
        dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).map { it.id }.toSet(),
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
            speciesName = newName,
        )
    val updateEvent =
        RecordedTreeUpdatedEvent(
            changedFrom =
                RecordedTreeUpdatedEventValues(
                    biomassSpeciesId = oldBiomassSpeciesId,
                    speciesId = speciesId,
                    speciesName = oldName,
                ),
            changedTo =
                RecordedTreeUpdatedEventValues(
                    biomassSpeciesId = newBiomassSpecies.id!!,
                    speciesId = newSpeciesId,
                    speciesName = newName,
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
            speciesName = oldName,
        )
    eventPublisher.assertExactEventsPublished(
        listOf(
            speciesCreatedEvent,
            updateEvent,
            updateEvent.copy(recordedTreeId = secondTrunkId),
            speciesDeletedEvent,
        )
    )
  }

  @Test
  fun `creates and reuses an Other species`() {
    val oldBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val firstTreeId = insertTree(oldBiomassSpeciesId)
    val secondTreeId = insertTree(oldBiomassSpeciesId, treeNumber = 2)
    val otherSpeciesName = "Other tree"

    store.changeRecordedTreeSpecies(
        observationId,
        monitoringPlotId,
        firstTreeId,
        null,
        otherSpeciesName,
    )
    store.changeRecordedTreeSpecies(
        observationId,
        monitoringPlotId,
        secondTreeId,
        null,
        otherSpeciesName,
    )

    val otherBiomassSpecies = dslContext.fetchSingle(OBSERVATION_BIOMASS_SPECIES)
    assertEquals(otherSpeciesName, otherBiomassSpecies.scientificName)
    assertEquals(
        setOf(otherBiomassSpecies.id),
        dslContext.fetch(RECORDED_TREES).map { it.biomassSpeciesId }.toSet(),
    )
  }

  @Test
  fun `keeps old species while a quadrat entry references it`() {
    val oldBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val treeId = insertTree(oldBiomassSpeciesId)
    insertObservationBiomassQuadratDetails(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
    )
    insertObservationBiomassQuadratSpecies(
        observationId = observationId,
        monitoringPlotId = monitoringPlotId,
        biomassSpeciesId = oldBiomassSpeciesId,
    )
    store.changeRecordedTreeSpecies(
        observationId,
        monitoringPlotId,
        treeId,
        insertSpecies(),
        null,
    )

    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(
        oldBiomassSpeciesId,
        dslContext.fetchSingle(OBSERVATION_BIOMASS_QUADRAT_SPECIES).biomassSpeciesId,
    )
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `requires quantity permission`() {
    every { user.canUpdateObservationQuantities(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.changeRecordedTreeSpecies(
          observationId,
          monitoringPlotId,
          RecordedTreeId(1),
          speciesId,
          null,
      )
    }
  }

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
