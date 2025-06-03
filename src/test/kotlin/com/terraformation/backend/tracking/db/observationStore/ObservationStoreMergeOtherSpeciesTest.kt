package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedPlotSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedSiteSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedZoneSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedPlantsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.SpeciesInWrongOrganizationException
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
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

class ObservationStoreMergeOtherSpeciesTest : BaseObservationStoreTest() {
  private lateinit var plantingZoneId: PlantingZoneId
  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var observationId: ObservationId

  @BeforeEach
  fun insertDetailedPlantingSite() {
    plantingZoneId = insertPlantingZone()
    insertPlantingSubzone()
    monitoringPlotId = insertMonitoringPlot()

    every { user.canUpdateSpecies(any()) } returns true
  }

  @Test
  fun `updates raw recorded plants data`() {
    val gpsCoordinates = point(1)
    val speciesId = insertSpecies()

    val observationId1 = insertObservation()
    insertObservationPlot()
    insertRecordedPlant(speciesName = "Species to merge", gpsCoordinates = gpsCoordinates)
    insertRecordedPlant(speciesName = "Other species", gpsCoordinates = gpsCoordinates)

    val observationId2 = insertObservation()
    insertObservationPlot()
    insertRecordedPlant(speciesName = "Species to merge", gpsCoordinates = gpsCoordinates)

    store.mergeOtherSpecies(observationId1, "Species to merge", speciesId)

    assertTableEquals(
        listOf(
            RecordedPlantsRecord(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId1,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRecord(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId1,
                speciesName = "Other species",
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRecord(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId2,
                speciesName = "Species to merge",
                statusId = RecordedPlantStatus.Live,
            ),
        ))
  }

  @Test
  fun `updates observed species totals`() {
    val gpsCoordinates = point(1)
    val speciesId = insertSpecies()

    val observationId1 = insertObservation()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    store.completePlot(
        observationId1,
        monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Live),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Dead),
        ))

    clock.instant = Instant.ofEpochSecond(1)

    val observationId2 = insertObservation()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    store.populateCumulativeDead(observationId2)

    store.completePlot(
        observationId2,
        monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Live),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Dead),
        ))

    val expectedPlotsBeforeMerge =
        listOf(
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = speciesId,
                speciesName = null,
                certaintyId = RecordedSpeciesCertainty.Known,
                totalLive = 1,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 0,
                cumulativeDead = 0,
                permanentLive = 1,
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = null,
                speciesName = "Merge",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 1,
                totalDead = 1,
                totalExisting = 0,
                mortalityRate = 50,
                cumulativeDead = 1,
                permanentLive = 1,
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId2,
                monitoringPlotId = monitoringPlotId,
                speciesId = speciesId,
                speciesName = null,
                certaintyId = RecordedSpeciesCertainty.Known,
                totalLive = 1,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 0,
                cumulativeDead = 0,
                permanentLive = 1,
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId2,
                monitoringPlotId = monitoringPlotId,
                speciesId = null,
                speciesName = "Merge",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 1,
                totalDead = 1,
                totalExisting = 0,
                mortalityRate = 67,
                cumulativeDead = 2,
                permanentLive = 1,
            ),
        )

    assertTableEquals(expectedPlotsBeforeMerge, "Before merge")
    assertTableEquals(expectedPlotsBeforeMerge.map { it.toZone() }, "Before merge")
    assertTableEquals(expectedPlotsBeforeMerge.map { it.toSite() }, "Before merge")

    store.mergeOtherSpecies(observationId1, "Merge", speciesId)

    val expectedPlotsAfterMerge =
        listOf(
            expectedPlotsBeforeMerge[0].apply {
              totalLive = 2
              totalDead = 1
              cumulativeDead = 1
              permanentLive = 2
              mortalityRate = 33
            },
            // expectedPlotsBeforeMerge[1] should be deleted
            expectedPlotsBeforeMerge[2].apply {
              cumulativeDead = 1
              mortalityRate = 50
            },
            expectedPlotsBeforeMerge[3].apply {
              cumulativeDead = 1
              mortalityRate = 50
            },
        )

    assertTableEquals(expectedPlotsAfterMerge, "After merge")
    assertTableEquals(expectedPlotsAfterMerge.map { it.toZone() }, "After merge")
    assertTableEquals(expectedPlotsAfterMerge.map { it.toSite() }, "After merge")
  }

  @Test
  fun `does not update zone or site species totals for ad-hoc observation`() {
    val gpsCoordinates = point(1)
    val speciesId = insertSpecies()

    monitoringPlotId = insertMonitoringPlot(plantingSubzoneId = null, isAdHoc = true)
    val observationId1 = insertObservation(isAdHoc = true)
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)

    store.completePlot(
        observationId1,
        monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Live),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Dead),
        ))

    clock.instant = Instant.ofEpochSecond(1)

    val expectedPlotsBeforeMerge =
        listOf(
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = speciesId,
                speciesName = null,
                certaintyId = RecordedSpeciesCertainty.Known,
                totalLive = 1,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = null,
                cumulativeDead = 0,
                permanentLive = 0,
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = null,
                speciesName = "Merge",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 1,
                totalDead = 1,
                totalExisting = 0,
                mortalityRate = null,
                cumulativeDead = 0,
                permanentLive = 0,
            ),
        )

    assertTableEquals(expectedPlotsBeforeMerge, "Before merge")
    assertTableEmpty(OBSERVED_ZONE_SPECIES_TOTALS)
    assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS)

    store.mergeOtherSpecies(observationId1, "Merge", speciesId)

    val expectedPlotsAfterMerge =
        listOf(
            expectedPlotsBeforeMerge[0].apply {
              totalLive = 2
              totalDead = 1
            },
            // expectedPlotsBeforeMerge[1] should be deleted
        )

    assertTableEquals(expectedPlotsAfterMerge, "After merge")
    assertTableEmpty(OBSERVED_ZONE_SPECIES_TOTALS)
    assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS)
  }

  @Test
  fun `updates entities for biomass observation`() {
    observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    monitoringPlotId = insertMonitoringPlot(plantingSubzoneId = null, isAdHoc = true)
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()

    store.insertBiomassDetails(
        observationId,
        monitoringPlotId,
        NewBiomassDetailsModel(
            forestType = BiomassForestType.Terrestrial,
            observationId = null,
            herbaceousCoverPercent = 30,
            quadrats =
                mapOf(
                    ObservationPlotPosition.NortheastCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 1, speciesId = speciesId1),
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 2, speciesId = speciesId2),
                                    // This will be merged with the speciesId1 entry
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 4, speciesName = "Other 1"),
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 8, speciesName = "Other 2"),
                                ),
                        ),
                    ObservationPlotPosition.NorthwestCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 16, speciesId = speciesId1),
                                    // This will be merged with the speciesId1 entry
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 32, speciesName = "Other 1"),
                                ),
                        ),
                    ObservationPlotPosition.SoutheastCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 51, speciesId = speciesId1),
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 52, speciesName = "Other 2"),
                                ),
                        ),
                    ObservationPlotPosition.SouthwestCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    // This should turn into a speciesId1 entry
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 53, speciesName = "Other 1"),
                                ),
                        ),
                ),
            smallTreeCountRange = 1 to 20,
            soilAssessment = "Dirty",
            species =
                setOf(
                    BiomassSpeciesModel(
                        isInvasive = false, isThreatened = true, speciesId = speciesId1),
                    BiomassSpeciesModel(
                        isInvasive = false, isThreatened = true, speciesId = speciesId2),
                    BiomassSpeciesModel(
                        isInvasive = true, isThreatened = false, scientificName = "Other 1"),
                    BiomassSpeciesModel(
                        isInvasive = false, isThreatened = false, scientificName = "Other 2"),
                ),
            plotId = null,
            trees =
                listOf(
                    NewRecordedTreeModel(
                        diameterAtBreastHeightCm = BigDecimal(1),
                        gpsCoordinates = point(1),
                        heightM = BigDecimal(10),
                        id = null,
                        isDead = false,
                        pointOfMeasurementM = BigDecimal(1.3),
                        speciesId = speciesId1,
                        treeNumber = 1,
                        trunkNumber = 1,
                        treeGrowthForm = TreeGrowthForm.Tree,
                    ),
                    NewRecordedTreeModel(
                        diameterAtBreastHeightCm = BigDecimal(1.5),
                        gpsCoordinates = point(2),
                        heightM = BigDecimal(11),
                        id = null,
                        isDead = false,
                        pointOfMeasurementM = BigDecimal(1.4),
                        speciesName = "Other 1",
                        treeNumber = 2,
                        trunkNumber = 1,
                        treeGrowthForm = TreeGrowthForm.Trunk,
                    ),
                    NewRecordedTreeModel(
                        diameterAtBreastHeightCm = BigDecimal(1.9),
                        gpsCoordinates = point(2),
                        id = null,
                        isDead = false,
                        pointOfMeasurementM = BigDecimal(1.2),
                        speciesName = "Other 1",
                        treeNumber = 2,
                        trunkNumber = 2,
                        treeGrowthForm = TreeGrowthForm.Trunk,
                    ),
                    NewRecordedTreeModel(
                        diameterAtBreastHeightCm = BigDecimal(12),
                        gpsCoordinates = null,
                        heightM = BigDecimal(30),
                        id = null,
                        isDead = false,
                        pointOfMeasurementM = BigDecimal(1.2),
                        speciesName = "Other 2",
                        treeNumber = 3,
                        trunkNumber = 1,
                        treeGrowthForm = TreeGrowthForm.Tree,
                    ),
                ),
        ))

    val recordedTreesBeforeMerge = dslContext.fetch(RECORDED_TREES)

    val observationSpeciesBeforeMerge = dslContext.fetch(OBSERVATION_BIOMASS_SPECIES)
    val biomassSpeciesId1 = observationSpeciesBeforeMerge.first { it.speciesId == speciesId1 }.id!!
    val biomassSpeciesId2 = observationSpeciesBeforeMerge.first { it.speciesId == speciesId2 }.id!!
    val biomassOtherId1 =
        observationSpeciesBeforeMerge.first { it.scientificName == "Other 1" }.id!!
    val biomassOtherId2 =
        observationSpeciesBeforeMerge.first { it.scientificName == "Other 2" }.id!!

    store.mergeOtherSpecies(observationId, "Other 1", speciesId1)

    assertTableEquals(
        recordedTreesBeforeMerge.map { record ->
          if (record.biomassSpeciesId == biomassOtherId1) {
            record.with(RECORDED_TREES.BIOMASS_SPECIES_ID, biomassSpeciesId1)
          } else {
            record
          }
        })

    assertTableEquals(
        setOf(
            // Abundance percent is 5 because we are combining the original speciesId1 entry's 1
            // with the "Other 1" entry's 4.
            quadratSpeciesRecord(ObservationPlotPosition.NortheastCorner, biomassSpeciesId1, 5),
            quadratSpeciesRecord(ObservationPlotPosition.NortheastCorner, biomassSpeciesId2, 2),
            quadratSpeciesRecord(ObservationPlotPosition.NortheastCorner, biomassOtherId2, 8),
            // And this one combines entries with abundances of 16 and 32 (to test that we aren't
            // combining the values from the wrong quadrats).
            quadratSpeciesRecord(ObservationPlotPosition.NorthwestCorner, biomassSpeciesId1, 48),
            // There's no "Other 1" in the southeast corner, so the abundance percent stays the same
            quadratSpeciesRecord(ObservationPlotPosition.SoutheastCorner, biomassSpeciesId1, 51),
            quadratSpeciesRecord(ObservationPlotPosition.SoutheastCorner, biomassOtherId2, 52),
            // This started out as an "Other 1" entry.
            quadratSpeciesRecord(ObservationPlotPosition.SouthwestCorner, biomassSpeciesId1, 53),
        ))

    assertTableEquals(
        setOf(
            // In the original data, "Species 1" is invasive and "Other 1" is threatened.
            observationSpeciesRecord(
                biomassSpeciesId1, speciesId1, isInvasive = true, isThreatened = true),
            observationSpeciesRecord(
                biomassSpeciesId2, speciesId2, isInvasive = false, isThreatened = true),
            observationSpeciesRecord(
                biomassOtherId2,
                speciesName = "Other 2",
                isInvasive = false,
                isThreatened = false)))
  }

  @Test
  fun `makes biomass species refer to target species ID if target was not already in observation`() {
    val observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    monitoringPlotId = insertMonitoringPlot(plantingSubzoneId = null, isAdHoc = true)
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)
    val speciesId = insertSpecies()

    store.insertBiomassDetails(
        observationId,
        monitoringPlotId,
        NewBiomassDetailsModel(
            forestType = BiomassForestType.Terrestrial,
            observationId = null,
            herbaceousCoverPercent = 30,
            quadrats =
                mapOf(
                    ObservationPlotPosition.NortheastCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 1, speciesName = "Other")))),
            smallTreeCountRange = 1 to 20,
            soilAssessment = "Dirty",
            species =
                setOf(
                    BiomassSpeciesModel(
                        isInvasive = true, isThreatened = false, scientificName = "Other")),
            plotId = null,
            trees =
                listOf(
                    NewRecordedTreeModel(
                        diameterAtBreastHeightCm = BigDecimal(15),
                        gpsCoordinates = point(1),
                        heightM = BigDecimal(11),
                        id = null,
                        isDead = false,
                        pointOfMeasurementM = BigDecimal(1.3),
                        speciesName = "Other",
                        treeNumber = 1,
                        trunkNumber = 1,
                        treeGrowthForm = TreeGrowthForm.Tree))))

    val quadratSpeciesBeforeMerge = dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
    val recordedTreesBeforeMerge = dslContext.fetch(RECORDED_TREES)

    store.mergeOtherSpecies(observationId, "Other", speciesId)

    assertTableEquals(
        ObservationBiomassSpeciesRecord(
            isInvasive = true,
            isThreatened = false,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            scientificName = null,
            speciesId = speciesId))

    // Other tables should be unmodified because we updated the biomass species in place.
    assertTableEquals(quadratSpeciesBeforeMerge)
    assertTableEquals(recordedTreesBeforeMerge)
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    val observationId = insertObservation()
    val speciesId = insertSpecies()

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.mergeOtherSpecies(observationId, "Other", speciesId)
    }
  }

  @Test
  fun `throws exception if no permission to update species`() {
    val observationId = insertObservation()
    val speciesId = insertSpecies()

    every { user.canReadSpecies(speciesId) } returns true
    every { user.canUpdateSpecies(speciesId) } returns false

    assertThrows<AccessDeniedException> {
      store.mergeOtherSpecies(observationId, "Other", speciesId)
    }
  }

  @Test
  fun `throws exception if species is from a different organization`() {
    val observationId = insertObservation()
    insertOrganization()
    val speciesId = insertSpecies()

    assertThrows<SpeciesInWrongOrganizationException> {
      store.mergeOtherSpecies(observationId, "Other", speciesId)
    }
  }

  private fun ObservedPlotSpeciesTotalsRecord.toZone(
      plantingZoneId: PlantingZoneId = inserted.plantingZoneId
  ) =
      ObservedZoneSpeciesTotalsRecord(
          observationId = observationId,
          plantingZoneId = plantingZoneId,
          speciesId = speciesId,
          speciesName = speciesName,
          certaintyId = certaintyId,
          totalLive = totalLive,
          totalDead = totalDead,
          totalExisting = totalExisting,
          mortalityRate = mortalityRate,
          cumulativeDead = cumulativeDead,
          permanentLive = permanentLive)

  private fun ObservedPlotSpeciesTotalsRecord.toSite(
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId
  ) =
      ObservedSiteSpeciesTotalsRecord(
          observationId = observationId,
          plantingSiteId = plantingSiteId,
          speciesId = speciesId,
          speciesName = speciesName,
          certaintyId = certaintyId,
          totalLive = totalLive,
          totalDead = totalDead,
          totalExisting = totalExisting,
          mortalityRate = mortalityRate,
          cumulativeDead = cumulativeDead,
          permanentLive = permanentLive)

  private fun quadratSpeciesRecord(
      position: ObservationPlotPosition,
      biomassSpeciesId: BiomassSpeciesId,
      abundancePercent: Int
  ) =
      ObservationBiomassQuadratSpeciesRecord(
          observationId, monitoringPlotId, position, biomassSpeciesId, abundancePercent)

  private fun observationSpeciesRecord(
      id: BiomassSpeciesId,
      speciesId: SpeciesId? = null,
      speciesName: String? = null,
      isInvasive: Boolean = false,
      isThreatened: Boolean = false
  ) =
      ObservationBiomassSpeciesRecord(
          id = id,
          observationId = observationId,
          monitoringPlotId = monitoringPlotId,
          speciesId = speciesId,
          scientificName = speciesName,
          isInvasive = isInvasive,
          isThreatened = isThreatened,
      )
}
