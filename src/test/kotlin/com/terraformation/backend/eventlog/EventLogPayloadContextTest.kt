package com.terraformation.backend.eventlog

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.eventlog.api.BiomassQuadratSpeciesSubjectPayload
import com.terraformation.backend.eventlog.api.BiomassSpeciesSubjectPayload
import com.terraformation.backend.eventlog.api.RecordedTreeSubjectPayload
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEventV2
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeDeletedEvent
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EventLogPayloadContextTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val messages = Messages()

  @Nested
  inner class GetBiomassSpecies {
    @BeforeEach
    fun setUp() {
      insertOrganization()
      insertPlantingSite(boundary = polygon(1))
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservation()
      insertObservationPlot()
      insertObservationBiomassDetails()
      insertSpecies(scientificName = "Species name")
    }

    @Test
    fun `looks up catalog and Other species names`() {
      val catalogId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)
      val otherId = insertObservationBiomassSpecies(scientificName = "Other species")
      val context = EventLogPayloadContext(dslContext, emptyList(), messages)

      assertEquals(
          EventLogPayloadContext.BiomassSpecies("Species name", null, inserted.speciesId),
          context.getBiomassSpecies(catalogId),
      )
      assertEquals(
          EventLogPayloadContext.BiomassSpecies("Other species", "Other species", null),
          context.getBiomassSpecies(otherId),
      )
      dslContext.update(SPECIES).set(SPECIES.SCIENTIFIC_NAME, "Changed").execute()
      dslContext
          .update(OBSERVATION_BIOMASS_SPECIES)
          .set(
              OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME,
              "Changed",
          )
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(otherId))
          .execute()
      assertEquals("Species name", context.getBiomassSpecies(catalogId).displayName)
      assertEquals("Other species", context.getBiomassSpecies(otherId).displayName)
    }

    @Test
    fun `uses created event name when biomass species row is deleted`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)
      val createdEvent =
          BiomassSpeciesCreatedEvent(
              biomassSpeciesId = biomassSpeciesId,
              commonName = null,
              isInvasive = false,
              isThreatened = false,
              monitoringPlotId = inserted.monitoringPlotId,
              observationId = inserted.observationId,
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              scientificName = null,
              speciesId = inserted.speciesId,
              speciesName = "Name from created event",
          )
      val context =
          EventLogPayloadContext(
              dslContext,
              listOf(eventLogEntry(createdEvent)),
              messages,
          )
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()

      assertEquals(
          "Name from created event",
          context.getBiomassSpecies(biomassSpeciesId).displayName,
      )

      val payload = BiomassSpeciesSubjectPayload.forEvent(createdEvent, context)

      assertEquals(inserted.speciesId, payload.speciesId)
      assertTrue(payload.fullText.contains("Name from created event"))
      val quadratSpeciesPayload =
          BiomassQuadratSpeciesSubjectPayload.forEvent(
              BiomassQuadratSpeciesUpdatedEventV2(
                  changedFrom = BiomassQuadratSpeciesUpdatedEventV2.Values(1),
                  changedTo = BiomassQuadratSpeciesUpdatedEventV2.Values(2),
                  biomassSpeciesId = biomassSpeciesId,
                  monitoringPlotId = inserted.monitoringPlotId,
                  observationId = inserted.observationId,
                  organizationId = inserted.organizationId,
                  plantingSiteId = inserted.plantingSiteId,
                  position = ObservationPlotPosition.SouthwestCorner,
                  speciesId = null,
                  speciesName = "Other name from event",
              ),
              context,
          )
      assertEquals(null, quadratSpeciesPayload.speciesId)
      assertEquals("Other name from event", quadratSpeciesPayload.scientificName)
      assertTrue(quadratSpeciesPayload.fullText.contains("Other name from event"))
    }

    @Test
    fun `renders deleted tree subjects from events`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)
      val treeEvent =
          RecordedTreeDeletedEvent(
              biomassSpeciesId = biomassSpeciesId,
              monitoringPlotId = inserted.monitoringPlotId,
              observationId = inserted.observationId,
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              recordedTreeId = RecordedTreeId(6),
              speciesId = null,
              speciesName = "Other species",
              treeGrowthForm = TreeGrowthForm.Trunk,
              treeNumber = 7,
              trunkNumber = 8,
          )
      val context =
          EventLogPayloadContext(
              dslContext,
              listOf(eventLogEntry(treeEvent)),
              messages,
          )

      val treePayload = RecordedTreeSubjectPayload.forEvent(treeEvent, context)

      assertEquals(true, treePayload.deleted)
      assertEquals(TreeGrowthForm.Trunk, treePayload.treeGrowthForm)
      assertEquals(7, treePayload.treeNumber)
      assertEquals(8, treePayload.trunkNumber)
    }

    @Test
    fun `uses quadrat species event order to determine deletion state`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)
      val createdEvent =
          BiomassQuadratSpeciesCreatedEvent(
              abundance = 4,
              biomassSpeciesId = biomassSpeciesId,
              monitoringPlotId = inserted.monitoringPlotId,
              observationId = inserted.observationId,
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              position = ObservationPlotPosition.SouthwestCorner,
              speciesId = inserted.speciesId,
              speciesName = "Species name from event",
          )
      val deletedEvent =
          BiomassQuadratSpeciesDeletedEvent(
              biomassSpeciesId = biomassSpeciesId,
              monitoringPlotId = inserted.monitoringPlotId,
              observationId = inserted.observationId,
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              position = ObservationPlotPosition.SouthwestCorner,
              speciesId = inserted.speciesId,
              speciesName = "Species name from event",
          )
      val context =
          EventLogPayloadContext(
              dslContext,
              listOf(eventLogEntry(createdEvent), eventLogEntry(deletedEvent)),
              messages,
          )

      assertEquals(
          true,
          BiomassQuadratSpeciesSubjectPayload.forEvent(createdEvent, context).deleted,
      )
      assertEquals(
          true,
          BiomassQuadratSpeciesSubjectPayload.forEvent(deletedEvent, context).deleted,
      )

      val deleteOnlyContext =
          EventLogPayloadContext(dslContext, listOf(eventLogEntry(deletedEvent)), messages)
      assertEquals(
          true,
          BiomassQuadratSpeciesSubjectPayload.forEvent(deletedEvent, deleteOnlyContext).deleted,
      )

      val recreatedContext =
          EventLogPayloadContext(
              dslContext,
              listOf(eventLogEntry(deletedEvent), eventLogEntry(createdEvent)),
              messages,
          )
      assertEquals(
          null,
          BiomassQuadratSpeciesSubjectPayload.forEvent(deletedEvent, recreatedContext).deleted,
      )
      assertEquals(
          null,
          BiomassQuadratSpeciesSubjectPayload.forEvent(createdEvent, recreatedContext).deleted,
      )
    }
  }

  private var nextEventLogId: Long = 1

  private fun eventLogEntry(event: PersistentEvent): EventLogEntry<PersistentEvent> =
      EventLogEntry(
          createdBy = user.userId,
          createdTime = Instant.ofEpochSecond(nextEventLogId),
          event = event,
          id = EventLogId(nextEventLogId++),
      )
}
