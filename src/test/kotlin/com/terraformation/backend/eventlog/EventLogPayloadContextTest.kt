package com.terraformation.backend.eventlog

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.eventlog.api.BiomassQuadratSpeciesSubjectPayload
import com.terraformation.backend.eventlog.api.BiomassSpeciesSubjectPayload
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEventV2
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEventV2
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
    fun `looks up species name from species ID`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)

      val context = EventLogPayloadContext(dslContext, emptyList(), messages)

      val expected = EventLogPayloadContext.BiomassSpecies("Species name", null, inserted.speciesId)

      assertEquals(expected, context.getBiomassSpecies(biomassSpeciesId), "Lookup from database")

      dslContext.update(SPECIES).set(SPECIES.SCIENTIFIC_NAME, "New name").execute()

      assertEquals(expected, context.getBiomassSpecies(biomassSpeciesId), "Lookup from cache")
    }

    @Test
    fun `looks up scientific name of Other species`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(scientificName = "Other species")

      val context = EventLogPayloadContext(dslContext, emptyList(), messages)

      val expected = EventLogPayloadContext.BiomassSpecies("Other species", "Other species", null)

      assertEquals(expected, context.getBiomassSpecies(biomassSpeciesId), "Lookup from database")

      dslContext
          .update(OBSERVATION_BIOMASS_SPECIES)
          .set(OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME, "New name")
          .execute()

      assertEquals(expected, context.getBiomassSpecies(biomassSpeciesId), "Lookup from cache")
    }

    @Test
    fun `uses name from creation event if available`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(scientificName = "Other species")

      val context =
          EventLogPayloadContext(
              dslContext,
              listOf(
                  eventLogEntry(
                      BiomassSpeciesCreatedEvent(
                          biomassSpeciesId,
                          null,
                          false,
                          false,
                          inserted.monitoringPlotId,
                          inserted.observationId,
                          inserted.organizationId,
                          inserted.plantingSiteId,
                          "Name from created event",
                          null,
                          "Name from created event",
                      )
                  )
              ),
              messages,
          )

      val expected =
          EventLogPayloadContext.BiomassSpecies(
              "Name from created event",
              "Name from created event",
              null,
          )

      assertEquals(expected, context.getBiomassSpecies(biomassSpeciesId))
    }

    @Test
    fun `uses created event name when biomass species row is deleted`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)
      val context =
          EventLogPayloadContext(
              dslContext,
              listOf(
                  eventLogEntry(
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
                  )
              ),
              messages,
          )
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()

      assertEquals(
          EventLogPayloadContext.BiomassSpecies(
              "Name from created event",
              null,
              inserted.speciesId,
          ),
          context.getBiomassSpecies(biomassSpeciesId),
      )
    }

    @Test
    fun `uses event names and species IDs for biomass subjects`() {
      val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = inserted.speciesId)
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()
      val context = EventLogPayloadContext(dslContext, emptyList(), messages)

      val biomassSpeciesPayload =
          BiomassSpeciesSubjectPayload.forEvent(
              BiomassSpeciesUpdatedEventV2(
                  changedFrom = BiomassSpeciesUpdatedEventV2.Values(false, false),
                  changedTo = BiomassSpeciesUpdatedEventV2.Values(true, true),
                  biomassSpeciesId = biomassSpeciesId,
                  monitoringPlotId = inserted.monitoringPlotId,
                  observationId = inserted.observationId,
                  organizationId = inserted.organizationId,
                  plantingSiteId = inserted.plantingSiteId,
                  speciesId = inserted.speciesId,
                  speciesName = "Catalog name from event",
              ),
              context,
          )
      assertEquals(inserted.speciesId, biomassSpeciesPayload.speciesId)
      assertTrue(biomassSpeciesPayload.fullText.contains("Catalog name from event"))

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
