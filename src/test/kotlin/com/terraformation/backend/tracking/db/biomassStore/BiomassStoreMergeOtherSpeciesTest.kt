package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassSpeciesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class BiomassStoreMergeOtherSpeciesTest : BaseBiomassStoreTest() {
  private lateinit var stratumId: StratumId
  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var observationId: ObservationId

  @BeforeEach
  fun insertDetailedPlantingSite() {
    stratumId = insertStratum()
    insertSubstratum()
    monitoringPlotId = insertMonitoringPlot()

    every { user.canUpdateSpecies(any()) } returns true
  }

  @Test
  fun `updates entities for biomass observation`() {
    observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    monitoringPlotId = insertMonitoringPlot(substratumId = null, isAdHoc = true)
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
                                        abundancePercent = 1,
                                        speciesId = speciesId1,
                                    ),
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 2,
                                        speciesId = speciesId2,
                                    ),
                                    // This will be merged with the speciesId1 entry
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 4,
                                        speciesName = "Other 1",
                                    ),
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 8,
                                        speciesName = "Other 2",
                                    ),
                                ),
                        ),
                    ObservationPlotPosition.NorthwestCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 16,
                                        speciesId = speciesId1,
                                    ),
                                    // This will be merged with the speciesId1 entry
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 32,
                                        speciesName = "Other 1",
                                    ),
                                ),
                        ),
                    ObservationPlotPosition.SoutheastCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 51,
                                        speciesId = speciesId1,
                                    ),
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 52,
                                        speciesName = "Other 2",
                                    ),
                                ),
                        ),
                    ObservationPlotPosition.SouthwestCorner to
                        BiomassQuadratModel(
                            species =
                                setOf(
                                    // This should turn into a speciesId1 entry
                                    BiomassQuadratSpeciesModel(
                                        abundancePercent = 53,
                                        speciesName = "Other 1",
                                    ),
                                ),
                        ),
                ),
            smallTreeCountRange = 1 to 20,
            soilAssessment = "Dirty",
            species =
                setOf(
                    BiomassSpeciesModel(
                        isInvasive = false,
                        isThreatened = true,
                        speciesId = speciesId1,
                    ),
                    BiomassSpeciesModel(
                        isInvasive = false,
                        isThreatened = true,
                        speciesId = speciesId2,
                    ),
                    BiomassSpeciesModel(
                        isInvasive = true,
                        isThreatened = false,
                        scientificName = "Other 1",
                    ),
                    BiomassSpeciesModel(
                        isInvasive = false,
                        isThreatened = false,
                        scientificName = "Other 2",
                    ),
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
        ),
    )

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
        }
    )

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
        )
    )

    assertTableEquals(
        setOf(
            // In the original data, "Species 1" is invasive and "Other 1" is threatened.
            observationSpeciesRecord(
                biomassSpeciesId1,
                speciesId1,
                isInvasive = true,
                isThreatened = true,
            ),
            observationSpeciesRecord(
                biomassSpeciesId2,
                speciesId2,
                isInvasive = false,
                isThreatened = true,
            ),
            observationSpeciesRecord(
                biomassOtherId2,
                speciesName = "Other 2",
                isInvasive = false,
                isThreatened = false,
            ),
        )
    )
  }

  @Test
  fun `makes biomass species refer to target species ID if target was not already in observation`() {
    val observationId =
        insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    monitoringPlotId = insertMonitoringPlot(substratumId = null, isAdHoc = true)
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
                                        abundancePercent = 1,
                                        speciesName = "Other",
                                    )
                                )
                        )
                ),
            smallTreeCountRange = 1 to 20,
            soilAssessment = "Dirty",
            species =
                setOf(
                    BiomassSpeciesModel(
                        isInvasive = true,
                        isThreatened = false,
                        scientificName = "Other",
                    )
                ),
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
                        treeGrowthForm = TreeGrowthForm.Tree,
                    )
                ),
        ),
    )

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
            speciesId = speciesId,
        )
    )

    // Other tables should be unmodified because we updated the biomass species in place.
    assertTableEquals(quadratSpeciesBeforeMerge)
    assertTableEquals(recordedTreesBeforeMerge)
  }

  private fun quadratSpeciesRecord(
      position: ObservationPlotPosition,
      biomassSpeciesId: BiomassSpeciesId,
      abundancePercent: Int,
  ) =
      ObservationBiomassQuadratSpeciesRecord(
          observationId,
          monitoringPlotId,
          position,
          biomassSpeciesId,
          abundancePercent,
      )

  private fun observationSpeciesRecord(
      id: BiomassSpeciesId,
      speciesId: SpeciesId? = null,
      speciesName: String? = null,
      isInvasive: Boolean = false,
      isThreatened: Boolean = false,
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
