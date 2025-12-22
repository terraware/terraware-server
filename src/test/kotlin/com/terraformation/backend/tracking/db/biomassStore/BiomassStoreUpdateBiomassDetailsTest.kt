package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEventValues
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreUpdateBiomassDetailsTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpBiomassDetails() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails(
        description = "Original description",
        herbaceousCoverPercent = 10,
        smallTreesCountLow = 1,
        smallTreesCountHigh = 4,
        soilAssessment = "Original soil assessment",
    )
  }

  @Test
  fun `updates editable fields for terrestrial observation`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(
          description = "New description",
          herbaceousCoverPercent = 13,
          ph = BigDecimal.ONE,
          salinityPpt = BigDecimal.ONE,
          smallTreeCountRange = 5 to 9,
          soilAssessment = "New soil assessment",
          tide = MangroveTide.Low,
          tideTime = Instant.EPOCH,
          waterDepthCm = 1,
      )
    }

    // Updates to mangrove-only values should be ignored
    val expected =
        before.copy().apply {
          description = "New description"
          herbaceousCoverPercent = 13
          smallTreesCountLow = 5
          smallTreesCountHigh = 9
          soilAssessment = "New soil assessment"
        }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassDetailsUpdatedEvent(
            changedFrom =
                BiomassDetailsUpdatedEventValues(
                    description = "Original description",
                    herbaceousCoverPercent = 10,
                    smallTreeCountRange = 1 to 4,
                    soilAssessment = "Original soil assessment",
                ),
            changedTo =
                BiomassDetailsUpdatedEventValues(
                    description = "New description",
                    herbaceousCoverPercent = 13,
                    smallTreeCountRange = 5 to 9,
                    soilAssessment = "New soil assessment",
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `can change terrestrial observation to mangrove`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(
          description = "New description",
          forestType = BiomassForestType.Mangrove,
          herbaceousCoverPercent = 13,
          ph = BigDecimal.ONE,
          salinityPpt = BigDecimal.TWO,
          smallTreeCountRange = 5 to 9,
          soilAssessment = "New soil assessment",
          tide = MangroveTide.Low,
          tideTime = Instant.EPOCH,
          waterDepthCm = 1,
      )
    }

    val expected =
        before.copy().apply {
          description = "New description"
          forestTypeId = BiomassForestType.Mangrove
          herbaceousCoverPercent = 13
          ph = BigDecimal.ONE
          salinityPpt = BigDecimal.TWO
          smallTreesCountLow = 5
          smallTreesCountHigh = 9
          soilAssessment = "New soil assessment"
          tideId = MangroveTide.Low
          tideTime = Instant.EPOCH
          waterDepthCm = 1
        }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassDetailsUpdatedEvent(
            changedFrom =
                BiomassDetailsUpdatedEventValues(
                    description = "Original description",
                    forestType = BiomassForestType.Terrestrial,
                    herbaceousCoverPercent = 10,
                    smallTreeCountRange = 1 to 4,
                    soilAssessment = "Original soil assessment",
                ),
            changedTo =
                BiomassDetailsUpdatedEventValues(
                    description = "New description",
                    forestType = BiomassForestType.Mangrove,
                    herbaceousCoverPercent = 13,
                    ph = BigDecimal.ONE,
                    salinity = BigDecimal.TWO,
                    smallTreeCountRange = 5 to 9,
                    soilAssessment = "New soil assessment",
                    tide = MangroveTide.Low,
                    tideTime = Instant.EPOCH,
                    waterDepth = 1,
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `clears mangrove-only values when changing from mangrove to terrestrial`() {
    dslContext.deleteFrom(OBSERVATION_BIOMASS_DETAILS).execute()
    insertObservationBiomassDetails(
        description = "Original description",
        forestType = BiomassForestType.Mangrove,
        herbaceousCoverPercent = 10,
        ph = BigDecimal.ONE,
        salinityPpt = BigDecimal.TWO,
        smallTreesCountLow = 1,
        smallTreesCountHigh = 4,
        soilAssessment = "Original soil assessment",
        tideId = MangroveTide.Low,
        tideTime = Instant.EPOCH,
        waterDepthCm = 1,
    )

    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(forestType = BiomassForestType.Terrestrial)
    }

    val expected =
        before.copy().apply {
          forestTypeId = BiomassForestType.Terrestrial
          ph = null
          salinityPpt = null
          tideId = null
          tideTime = null
          waterDepthCm = null
        }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassDetailsUpdatedEvent(
            changedFrom =
                BiomassDetailsUpdatedEventValues(
                    forestType = BiomassForestType.Mangrove,
                    ph = BigDecimal.ONE,
                    salinity = BigDecimal.TWO,
                    tide = MangroveTide.Low,
                    tideTime = Instant.EPOCH,
                    waterDepth = 1,
                ),
            changedTo =
                BiomassDetailsUpdatedEventValues(forestType = BiomassForestType.Terrestrial),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `omits unmodified values from events`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(description = "New description")
    }

    val expected = before.copy().apply { description = "New description" }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassDetailsUpdatedEvent(
            changedFrom = BiomassDetailsUpdatedEventValues(description = "Original description"),
            changedTo = BiomassDetailsUpdatedEventValues(description = "New description"),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `does not publish event or modify database if nothing changed`() {
    val unmodifiedTable = dslContext.fetch(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) { it }

    assertTableEquals(unmodifiedTable)

    eventPublisher.assertEventNotPublished<BiomassDetailsUpdatedEvent>()
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    every { user.canUpdateObservation(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) { it }
    }
  }
}
