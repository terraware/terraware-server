package com.terraformation.backend.tracking.db.biomassStore

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
  fun `changes every trunk, leaves other trees alone, and publishes exact events`() {
    val oldBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val oldName = dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(speciesId))!!
    val newSpeciesId = insertSpecies()
    val newName = dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(newSpeciesId))!!
    val newTreeId = insertTree(oldBiomassSpeciesId, treeGrowthForm = TreeGrowthForm.Trunk)
    val secondTrunkId =
        insertTree(oldBiomassSpeciesId, trunkNumber = 2, treeGrowthForm = TreeGrowthForm.Trunk)
    val otherSpeciesId = insertSpecies()
    val otherBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = otherSpeciesId)
    val otherTreeId = insertTree(otherBiomassSpeciesId, treeNumber = 2)

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
    val records = dslContext.selectFrom(RECORDED_TREES).fetch().associateBy { it.id }
    assertEquals(newBiomassSpecies.id, records[newTreeId]!!.biomassSpeciesId)
    assertEquals(newBiomassSpecies.id, records[secondTrunkId]!!.biomassSpeciesId)
    assertEquals(otherBiomassSpeciesId, records[otherTreeId]!!.biomassSpeciesId)
    assertEquals(
        setOf(newBiomassSpecies.id, otherBiomassSpeciesId),
        dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).map { it.id }.toSet(),
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
    eventPublisher.assertEventsPublished(
        listOf(updateEvent, updateEvent.copy(recordedTreeId = secondTrunkId))
    )
    eventPublisher.assertEventNotPublished(updateEvent.copy(recordedTreeId = otherTreeId))
    eventPublisher.assertEventPublished(
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
    )
    eventPublisher.assertEventPublished(
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
    )
  }

  @Test
  fun `creates an Other species once and reuses it for another tree`() {
    val oldBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val firstTreeId = insertTree(oldBiomassSpeciesId)
    val secondTreeId = insertTree(oldBiomassSpeciesId, treeNumber = 2)

    store.changeRecordedTreeSpecies(
        observationId,
        monitoringPlotId,
        firstTreeId,
        null,
        "Other tree",
    )
    val otherBiomassSpecies =
        dslContext
            .selectFrom(OBSERVATION_BIOMASS_SPECIES)
            .where(OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME.eq("Other tree"))
            .fetchSingle()
    store.changeRecordedTreeSpecies(
        observationId,
        monitoringPlotId,
        secondTreeId,
        null,
        "Other tree",
    )

    assertEquals(
        setOf(otherBiomassSpecies.id),
        dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).map { it.id }.toSet(),
    )
    assertEquals(
        setOf(otherBiomassSpecies.id),
        dslContext.fetch(RECORDED_TREES).map { it.biomassSpeciesId }.toSet(),
    )
    eventPublisher.assertEventsPublished(
        listOf(
            BiomassSpeciesCreatedEvent(
                biomassSpeciesId = otherBiomassSpecies.id!!,
                commonName = null,
                isInvasive = false,
                isThreatened = false,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                scientificName = "Other tree",
                speciesId = null,
                speciesName = "Other tree",
            )
        )
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
    val before = dslContext.fetch(RECORDED_TREES)

    store.changeRecordedTreeSpecies(observationId, monitoringPlotId, treeId, speciesId, null)

    assertEquals(before, dslContext.fetch(RECORDED_TREES))
    eventPublisher.assertNoEventsPublished()

    store.changeRecordedTreeSpecies(observationId, monitoringPlotId, treeId, insertSpecies(), null)

    assertEquals(2, dslContext.fetch(OBSERVATION_BIOMASS_SPECIES).size)
    assertEquals(1, dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES).size)
    eventPublisher.assertEventNotPublished<BiomassSpeciesDeletedEvent>()
  }

  @Test
  fun `requires quantity permission`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val treeId = insertTree(biomassSpeciesId)
    every { user.canUpdateObservationQuantities(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.changeRecordedTreeSpecies(observationId, monitoringPlotId, treeId, speciesId, null)
    }
  }

  @Test
  fun `does not find trees from another observation`() {
    val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = speciesId)
    val treeId = insertTree(biomassSpeciesId)
    val otherObservationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)

    assertThrows<RecordedTreeNotFoundException> {
      store.changeRecordedTreeSpecies(
          otherObservationId,
          monitoringPlotId,
          treeId,
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
