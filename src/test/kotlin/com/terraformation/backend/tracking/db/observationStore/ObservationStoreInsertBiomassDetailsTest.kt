package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedTreesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.event.BiomassDetailsCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesKey
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreInsertBiomassDetailsTest : BaseObservationStoreTest() {
  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId

  @BeforeEach
  fun insertPlotAndObservation() {
    plotId = insertMonitoringPlot(isAdHoc = true)
    observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(claimedBy = currentUser().userId, claimedTime = clock.instant)
  }

  @Test
  fun `inserts required biomass detail, quadrat species and details, trees and branches`() {
    val herbaceousSpeciesId1 = insertSpecies()
    val herbaceousSpeciesId2 = insertSpecies()

    val treeSpeciesId1 = insertSpecies()
    val treeSpeciesId2 = insertSpecies()

    val model =
        NewBiomassDetailsModel(
            description = "description",
            forestType = BiomassForestType.Mangrove,
            herbaceousCoverPercent = 10,
            observationId = null,
            ph = BigDecimal.valueOf(6.5),
            quadrats =
                mapOf(
                    ObservationPlotPosition.NortheastCorner to
                        BiomassQuadratModel(
                            description = "NE description",
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 40,
                                        speciesId = herbaceousSpeciesId1,
                                    )
                                ),
                        ),
                    ObservationPlotPosition.NorthwestCorner to
                        BiomassQuadratModel(
                            description = "NW description",
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 60,
                                        speciesId = herbaceousSpeciesId2,
                                    ),
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 5,
                                        speciesName = "Other herbaceous species",
                                    ),
                                ),
                        ),
                    ObservationPlotPosition.SoutheastCorner to
                        BiomassQuadratModel(
                            description = "SE description",
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 90,
                                        speciesId = herbaceousSpeciesId1,
                                    )
                                ),
                        ),
                    ObservationPlotPosition.SouthwestCorner to
                        BiomassQuadratModel(
                            description = "SW description",
                            species = emptySet(),
                        ),
                ),
            salinityPpt = BigDecimal.valueOf(20),
            smallTreeCountRange = 0 to 10,
            soilAssessment = "soil",
            species =
                setOf(
                    BiomassSpeciesModel(
                        speciesId = herbaceousSpeciesId1,
                        isInvasive = false,
                        isThreatened = false,
                    ),
                    BiomassSpeciesModel(
                        speciesId = herbaceousSpeciesId2,
                        isInvasive = false,
                        isThreatened = true,
                    ),
                    BiomassSpeciesModel(
                        scientificName = "Other herbaceous species",
                        commonName = "Common herb",
                        isInvasive = true,
                        isThreatened = false,
                    ),
                    BiomassSpeciesModel(
                        speciesId = treeSpeciesId1,
                        isInvasive = false,
                        isThreatened = true,
                    ),
                    BiomassSpeciesModel(
                        speciesId = treeSpeciesId2,
                        isInvasive = false,
                        isThreatened = false,
                    ),
                    BiomassSpeciesModel(
                        scientificName = "Other tree species",
                        commonName = "Common tree",
                        isInvasive = false,
                        isThreatened = false,
                    ),
                ),
            plotId = null,
            tide = MangroveTide.High,
            tideTime = Instant.ofEpochSecond(123),
            trees =
                listOf(
                    NewRecordedTreeModel(
                        id = null,
                        isDead = false,
                        diameterAtBreastHeightCm = BigDecimal.TWO, // this value is ignored
                        gpsCoordinates = point(1),
                        pointOfMeasurementM = BigDecimal.valueOf(1.3), // ignored
                        shrubDiameterCm = 25,
                        speciesId = treeSpeciesId1,
                        treeGrowthForm = TreeGrowthForm.Shrub,
                        treeNumber = 1,
                        trunkNumber = 1,
                    ),
                    NewRecordedTreeModel(
                        id = null,
                        isDead = true,
                        diameterAtBreastHeightCm = BigDecimal.TWO,
                        gpsCoordinates = null,
                        pointOfMeasurementM = BigDecimal.valueOf(1.3),
                        heightM = BigDecimal.TEN,
                        shrubDiameterCm = 1, // ignored
                        speciesName = "Other tree species",
                        treeGrowthForm = TreeGrowthForm.Tree,
                        treeNumber = 2,
                        trunkNumber = 1,
                    ),
                    NewRecordedTreeModel(
                        id = null,
                        isDead = false,
                        diameterAtBreastHeightCm = BigDecimal.TEN,
                        gpsCoordinates = point(2),
                        pointOfMeasurementM = BigDecimal.valueOf(1.5),
                        heightM = BigDecimal.TEN,
                        speciesId = treeSpeciesId2,
                        treeGrowthForm = TreeGrowthForm.Trunk,
                        treeNumber = 3,
                        trunkNumber = 1,
                    ),
                    NewRecordedTreeModel(
                        id = null,
                        diameterAtBreastHeightCm = BigDecimal.TWO,
                        gpsCoordinates = point(2),
                        pointOfMeasurementM = BigDecimal.valueOf(1.1),
                        isDead = false,
                        speciesId = treeSpeciesId2,
                        treeGrowthForm = TreeGrowthForm.Trunk,
                        treeNumber = 3,
                        trunkNumber = 2,
                    ),
                ),
            waterDepthCm = 2,
        )

    store.insertBiomassDetails(observationId, plotId, model)

    assertTableEquals(
        ObservationBiomassDetailsRecord(
            observationId = observationId,
            monitoringPlotId = plotId,
            description = "description",
            forestTypeId = BiomassForestType.Mangrove,
            herbaceousCoverPercent = 10,
            ph = BigDecimal.valueOf(6.5),
            salinityPpt = BigDecimal.valueOf(20),
            smallTreesCountHigh = 10,
            smallTreesCountLow = 0,
            soilAssessment = "soil",
            tideId = MangroveTide.High,
            tideTime = Instant.ofEpochSecond(123),
            waterDepthCm = 2,
        ),
        "Biomass details table",
    )

    eventPublisher.assertEventPublished(
        BiomassDetailsCreatedEvent(
            description = "description",
            forestType = BiomassForestType.Mangrove,
            herbaceousCoverPercent = 10,
            monitoringPlotId = plotId,
            observationId = observationId,
            organizationId = organizationId,
            ph = BigDecimal.valueOf(6.5),
            plantingSiteId = plantingSiteId,
            salinityPpt = BigDecimal.valueOf(20),
            smallTreesCountHigh = 10,
            smallTreesCountLow = 0,
            soilAssessment = "soil",
            tide = MangroveTide.High,
            tideTime = Instant.ofEpochSecond(123),
            waterDepthCm = 2,
        )
    )

    val biomassSpeciesIdsBySpeciesKey =
        observationBiomassSpeciesDao.findAll().associate {
          BiomassSpeciesKey(it.speciesId, it.scientificName) to it.id
        }

    val biomassHerbaceousSpeciesId1 =
        biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = herbaceousSpeciesId1)]!!
    val biomassHerbaceousSpeciesId2 =
        biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = herbaceousSpeciesId2)]!!
    val biomassHerbaceousSpeciesId3 =
        biomassSpeciesIdsBySpeciesKey[
            BiomassSpeciesKey(scientificName = "Other herbaceous species")]!!
    val biomassTreeSpeciesId1 =
        biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = treeSpeciesId1)]!!
    val biomassTreeSpeciesId2 =
        biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = treeSpeciesId2)]!!
    val biomassTreeSpeciesId3 =
        biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(scientificName = "Other tree species")]!!

    assertTableEquals(
        setOf(
            ObservationBiomassSpeciesRecord(
                id = biomassHerbaceousSpeciesId1,
                observationId = observationId,
                monitoringPlotId = plotId,
                speciesId = herbaceousSpeciesId1,
                isInvasive = false,
                isThreatened = false,
            ),
            ObservationBiomassSpeciesRecord(
                id = biomassHerbaceousSpeciesId2,
                observationId = observationId,
                monitoringPlotId = plotId,
                speciesId = herbaceousSpeciesId2,
                isInvasive = false,
                isThreatened = true,
            ),
            ObservationBiomassSpeciesRecord(
                id = biomassHerbaceousSpeciesId3,
                observationId = observationId,
                monitoringPlotId = plotId,
                scientificName = "Other herbaceous species",
                commonName = "Common herb",
                isInvasive = true,
                isThreatened = false,
            ),
            ObservationBiomassSpeciesRecord(
                id = biomassTreeSpeciesId1,
                observationId = observationId,
                monitoringPlotId = plotId,
                speciesId = treeSpeciesId1,
                isInvasive = false,
                isThreatened = true,
            ),
            ObservationBiomassSpeciesRecord(
                id = biomassTreeSpeciesId2,
                observationId = observationId,
                monitoringPlotId = plotId,
                speciesId = treeSpeciesId2,
                isInvasive = false,
                isThreatened = false,
            ),
            ObservationBiomassSpeciesRecord(
                id = biomassTreeSpeciesId3,
                observationId = observationId,
                monitoringPlotId = plotId,
                scientificName = "Other tree species",
                commonName = "Common tree",
                isInvasive = false,
                isThreatened = false,
            ),
        ),
        "Biomass species table",
    )

    eventPublisher.assertEventsPublished(
        dslContext
            .fetch(OBSERVATION_BIOMASS_SPECIES)
            .map { record ->
              BiomassSpeciesCreatedEvent(
                  biomassSpeciesId = record.id!!,
                  commonName = record.commonName,
                  isInvasive = record.isInvasive!!,
                  isThreatened = record.isThreatened!!,
                  monitoringPlotId = plotId,
                  observationId = observationId,
                  organizationId = organizationId,
                  plantingSiteId = plantingSiteId,
                  scientificName = record.scientificName,
                  speciesId = record.speciesId,
              )
            }
            .toSet()
    )

    assertTableEquals(
        setOf(
            ObservationBiomassQuadratDetailsRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.NortheastCorner,
                description = "NE description",
            ),
            ObservationBiomassQuadratDetailsRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.NorthwestCorner,
                description = "NW description",
            ),
            ObservationBiomassQuadratDetailsRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.SoutheastCorner,
                description = "SE description",
            ),
            ObservationBiomassQuadratDetailsRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.SouthwestCorner,
                description = "SW description",
            ),
        ),
        "Biomass quadrat details table",
    )

    eventPublisher.assertEventsPublished(
        dslContext
            .fetch(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
            .map { record ->
              BiomassQuadratCreatedEvent(
                  record.description,
                  plotId,
                  observationId,
                  organizationId,
                  plantingSiteId,
                  record.positionId!!,
              )
            }
            .toSet()
    )

    assertTableEquals(
        listOf(
            ObservationBiomassQuadratSpeciesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.NortheastCorner,
                abundancePercent = 40,
                biomassSpeciesId = biomassHerbaceousSpeciesId1,
            ),
            ObservationBiomassQuadratSpeciesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.NorthwestCorner,
                abundancePercent = 60,
                biomassSpeciesId = biomassHerbaceousSpeciesId2,
            ),
            ObservationBiomassQuadratSpeciesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.NorthwestCorner,
                abundancePercent = 5,
                biomassSpeciesId = biomassHerbaceousSpeciesId3,
            ),
            ObservationBiomassQuadratSpeciesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                positionId = ObservationPlotPosition.SoutheastCorner,
                abundancePercent = 90,
                biomassSpeciesId = biomassHerbaceousSpeciesId1,
            ),
        ),
        "Biomass quadrat species table",
    )

    assertTableEquals(
        listOf(
            RecordedTreesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                biomassSpeciesId = biomassTreeSpeciesId1,
                gpsCoordinates = point(1),
                isDead = false,
                shrubDiameterCm = 25,
                treeGrowthFormId = TreeGrowthForm.Shrub,
                treeNumber = 1,
                trunkNumber = 1,
            ),
            RecordedTreesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                biomassSpeciesId = biomassTreeSpeciesId3,
                diameterAtBreastHeightCm = BigDecimal.TWO,
                heightM = BigDecimal.TEN,
                pointOfMeasurementM = BigDecimal.valueOf(1.3),
                isDead = true,
                treeGrowthFormId = TreeGrowthForm.Tree,
                treeNumber = 2,
                trunkNumber = 1,
            ),
            RecordedTreesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                biomassSpeciesId = biomassTreeSpeciesId2,
                diameterAtBreastHeightCm = BigDecimal.TEN,
                gpsCoordinates = point(2),
                pointOfMeasurementM = BigDecimal.valueOf(1.5),
                heightM = BigDecimal.TEN,
                isDead = false,
                treeGrowthFormId = TreeGrowthForm.Trunk,
                treeNumber = 3,
                trunkNumber = 1,
            ),
            RecordedTreesRecord(
                observationId = observationId,
                monitoringPlotId = plotId,
                biomassSpeciesId = biomassTreeSpeciesId2,
                diameterAtBreastHeightCm = BigDecimal.TWO,
                gpsCoordinates = point(2),
                pointOfMeasurementM = BigDecimal.valueOf(1.1),
                isDead = false,
                treeGrowthFormId = TreeGrowthForm.Trunk,
                treeNumber = 3,
                trunkNumber = 2,
            ),
        ),
        "Recorded trees table",
    )

    val recordedTreeIdsByTreeAndTrunk =
        dslContext.fetch(RECORDED_TREES).associate { (it.treeNumber to it.trunkNumber) to it.id }

    eventPublisher.assertEventsPublished(
        setOf(
            RecordedTreeCreatedEvent(
                biomassSpeciesId = biomassTreeSpeciesId1,
                gpsCoordinates = point(1),
                isDead = false,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                recordedTreeId = recordedTreeIdsByTreeAndTrunk[1 to 1]!!,
                shrubDiameterCm = 25,
                speciesId = treeSpeciesId1,
                treeGrowthForm = TreeGrowthForm.Shrub,
                treeNumber = 1,
                trunkNumber = 1,
            ),
            RecordedTreeCreatedEvent(
                biomassSpeciesId = biomassTreeSpeciesId3,
                diameterAtBreastHeightCm = BigDecimal.TWO,
                heightM = BigDecimal.TEN,
                isDead = true,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                pointOfMeasurementM = BigDecimal.valueOf(1.3),
                recordedTreeId = recordedTreeIdsByTreeAndTrunk[2 to 1]!!,
                speciesName = "Other tree species",
                treeGrowthForm = TreeGrowthForm.Tree,
                treeNumber = 2,
                trunkNumber = 1,
            ),
            RecordedTreeCreatedEvent(
                biomassSpeciesId = biomassTreeSpeciesId2,
                diameterAtBreastHeightCm = BigDecimal.TEN,
                gpsCoordinates = point(2),
                heightM = BigDecimal.TEN,
                isDead = false,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                pointOfMeasurementM = BigDecimal.valueOf(1.5),
                recordedTreeId = recordedTreeIdsByTreeAndTrunk[3 to 1]!!,
                speciesId = treeSpeciesId2,
                treeGrowthForm = TreeGrowthForm.Trunk,
                treeNumber = 3,
                trunkNumber = 1,
            ),
            RecordedTreeCreatedEvent(
                biomassSpeciesId = biomassTreeSpeciesId2,
                diameterAtBreastHeightCm = BigDecimal.TWO,
                gpsCoordinates = point(2),
                isDead = false,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                pointOfMeasurementM = BigDecimal.valueOf(1.1),
                recordedTreeId = recordedTreeIdsByTreeAndTrunk[3 to 2]!!,
                speciesId = treeSpeciesId2,
                treeGrowthForm = TreeGrowthForm.Trunk,
                treeNumber = 3,
                trunkNumber = 2,
            ),
        )
    )
  }

  @Test
  fun `throws exception if no permission`() {
    val model =
        NewBiomassDetailsModel(
            description = "Basic biomass details",
            forestType = BiomassForestType.Terrestrial,
            herbaceousCoverPercent = 0,
            observationId = null,
            smallTreeCountRange = 0 to 0,
            soilAssessment = "Basic soil assessment",
            plotId = null,
        )

    every { user.canUpdateObservation(any()) } returns false

    assertThrows<AccessDeniedException> { store.insertBiomassDetails(observationId, plotId, model) }

    every { user.canReadObservation(any()) } returns false

    assertThrows<ObservationNotFoundException> {
      store.insertBiomassDetails(observationId, plotId, model)
    }
  }
}
