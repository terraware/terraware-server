package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassAdditionalSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadrantDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadrantSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedBranchesRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedTreesRecord
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

class ObservationBiomassDatabaseTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private lateinit var organizationId: OrganizationId
  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId
  private lateinit var speciesId1: SpeciesId
  private lateinit var speciesId2: SpeciesId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
    insertOrganizationUser(role = Role.Admin)
    insertPlantingSite(x = 0)
    insertPlantingSiteHistory()
    observationId =
        insertObservation(observationType = ObservationType.BiomassMeasurements, isAdHoc = true)
    plotId = insertMonitoringPlot(isAdHoc = true)
    insertObservationPlot()
  }

  @Nested
  inner class ObservationBiomassDetailsTable {
    @Test
    fun `throws exception if inserting row for plot not in observation`() {
      val otherPlotId = insertMonitoringPlot()

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId, monitoringPlotId = otherPlotId)
      }
    }

    @Test
    fun `throws exception if inserting a second row`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      }
    }

    @Test
    fun `throws exception if small tree counts are negative `() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId, monitoringPlotId = plotId, smallTreesCountLow = -1)
      }
    }

    @Test
    fun `throws exception if small tree count range is invalid `() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            smallTreesCountLow = 1,
            smallTreesCountHigh = 0)
      }
    }

    @Test
    fun `throws exception if herbaceous cover is negative`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId, monitoringPlotId = plotId, herbaceousCoverPercent = -1.0)
      }
    }

    @Test
    fun `throws exception if herbaceous cover is over 100`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            herbaceousCoverPercent = 101.0)
      }
    }

    @Test
    fun `throws exception if mangrove values are provided for terrestrial forest`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            forestType = BiomassForestType.Terrestrial,
            waterDepthCm = -1.0,
            salinityPpt = 0.0,
            tideId = MangroveTide.Low,
            tideTime = Instant.EPOCH)
      }
    }

    @Test
    fun `throws exception if water depth is negative for mangrove forest`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            forestType = BiomassForestType.Mangrove,
            waterDepthCm = -1.0,
            salinityPpt = 0.0,
            tideId = MangroveTide.Low,
            tideTime = Instant.EPOCH)
      }
    }

    @Test
    fun `throws exception if water depth is null for mangrove forest`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            forestType = BiomassForestType.Mangrove,
            waterDepthCm = null,
            salinityPpt = 0.0,
            tideId = MangroveTide.Low,
            tideTime = Instant.EPOCH)
      }
    }

    @Test
    fun `throws exception if salinity is negative for mangrove forest`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            forestType = BiomassForestType.Mangrove,
            waterDepthCm = 0.0,
            salinityPpt = -1.0,
            tideId = MangroveTide.Low,
            tideTime = Instant.EPOCH)
      }
    }

    @Test
    fun `throws exception if salinity is null for mangrove forest`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            forestType = BiomassForestType.Mangrove,
            waterDepthCm = 0.0,
            salinityPpt = null,
            tideId = MangroveTide.Low,
            tideTime = Instant.EPOCH)
      }
    }

    @Test
    fun `throws exception if tide is null for mangrove forest`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            forestType = BiomassForestType.Mangrove,
            waterDepthCm = 0.0,
            salinityPpt = 0.0,
            tideId = null,
            tideTime = Instant.EPOCH)
      }
    }

    @Test
    fun `throws exception if tide time is null for mangrove forest`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            forestType = BiomassForestType.Mangrove,
            waterDepthCm = 0.0,
            salinityPpt = 0.0,
            tideId = MangroveTide.Low,
            tideTime = null)
      }
    }
  }

  @Nested
  inner class ObservationBiomassQuadrantDetailsTable {
    @Test
    fun `throws exception if inserting quadrant details without biomass details`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            description = "Description")
      }
    }

    @Test
    fun `throws exception if same quadrant inserted twice`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassQuadrantDetails(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.SouthwestCorner,
          description = "First insert")

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantDetails(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            description = "Second insert")
      }
    }

    @Test
    fun `deletes associated quadrant details if biomass details row is deleted`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassQuadrantDetails(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.SouthwestCorner,
          description = "SW description")

      insertObservationBiomassQuadrantDetails(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.NortheastCorner,
          description = "NE description")

      val otherObservationId =
          insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
      val otherPlotId = insertMonitoringPlot(isAdHoc = true)
      insertObservationPlot(observationId = otherObservationId, monitoringPlotId = otherPlotId)
      insertObservationBiomassDetails(
          observationId = otherObservationId, monitoringPlotId = otherPlotId)
      insertObservationBiomassQuadrantDetails(
          observationId = otherObservationId,
          monitoringPlotId = otherPlotId,
          position = ObservationPlotPosition.NorthwestCorner,
          description = "Other NW description")

      assertTableEquals(
          listOf(
              ObservationBiomassQuadrantDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.SouthwestCorner,
                  description = "SW description",
              ),
              ObservationBiomassQuadrantDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.NortheastCorner,
                  description = "NE description",
              ),
              ObservationBiomassQuadrantDetailsRecord(
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  positionId = ObservationPlotPosition.NorthwestCorner,
                  description = "Other NW description",
              ),
          ),
          "Table before deletion")

      deleteObservationBiomassDetails(observationId, plotId)

      assertTableEquals(
          listOf(
              ObservationBiomassQuadrantDetailsRecord(
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  positionId = ObservationPlotPosition.NorthwestCorner,
                  description = "Other NW description",
              ),
          ),
          "Table after deletion")
    }
  }

  @Nested
  inner class ObservationBiomassQuadrantSpeciesTable {
    private lateinit var speciesId1: SpeciesId
    private lateinit var speciesId2: SpeciesId

    @BeforeEach
    fun `insert species`() {
      speciesId1 = insertSpecies()
      speciesId2 = insertSpecies()
    }

    @Test
    fun `throws exception if inserting quadrant species without biomass details`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            speciesId = speciesId1,
        )
      }
    }

    @Test
    fun `throws exception if same species Id inserted twice in the same quadrant`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassQuadrantSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.SouthwestCorner,
          speciesId = speciesId1,
      )

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            speciesId = speciesId1,
        )
      }
    }

    @Test
    fun `throws exception if same species name inserted twice in the same quadrant`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassQuadrantSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.SouthwestCorner,
          speciesName = "Other Species",
      )

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            speciesName = "Other Species",
        )
      }
    }

    @Test
    fun `throws exception if both species ID and name are null`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            speciesName = null,
            speciesId = null,
        )
      }
    }

    @Test
    fun `throws exception if abundance percent is negative`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            speciesId = speciesId1,
            abundancePercent = -1.0,
        )
      }
    }

    @Test
    fun `throws exception if abundance percent is over 100`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassQuadrantSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = ObservationPlotPosition.SouthwestCorner,
            speciesId = speciesId1,
            abundancePercent = 101.0,
        )
      }
    }

    @Test
    fun `deletes associated quadrant species if biomass details row is deleted`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassQuadrantSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.SouthwestCorner,
          speciesId = speciesId1,
          isThreatened = true,
      )

      insertObservationBiomassQuadrantSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.SouthwestCorner,
          speciesId = speciesId2,
          isInvasive = true,
      )

      insertObservationBiomassQuadrantSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          position = ObservationPlotPosition.NortheastCorner,
          speciesId = speciesId1,
          isThreatened = true,
      )

      val otherObservationId =
          insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
      val otherPlotId = insertMonitoringPlot(isAdHoc = true)
      insertObservationPlot(observationId = otherObservationId, monitoringPlotId = otherPlotId)
      insertObservationBiomassDetails(
          observationId = otherObservationId, monitoringPlotId = otherPlotId)
      insertObservationBiomassQuadrantSpecies(
          observationId = otherObservationId,
          monitoringPlotId = otherPlotId,
          position = ObservationPlotPosition.NortheastCorner,
          speciesId = speciesId1,
          isThreatened = true,
      )

      assertTableEquals(
          listOf(
              ObservationBiomassQuadrantSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.SouthwestCorner,
                  speciesId = speciesId1,
                  isThreatened = true,
                  isInvasive = false,
                  abundancePercent = 0.0,
              ),
              ObservationBiomassQuadrantSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.SouthwestCorner,
                  speciesId = speciesId2,
                  isThreatened = false,
                  isInvasive = true,
                  abundancePercent = 0.0,
              ),
              ObservationBiomassQuadrantSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.NortheastCorner,
                  speciesId = speciesId1,
                  isThreatened = true,
                  isInvasive = false,
                  abundancePercent = 0.0,
              ),
              ObservationBiomassQuadrantSpeciesRecord(
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  positionId = ObservationPlotPosition.NortheastCorner,
                  speciesId = speciesId1,
                  isThreatened = true,
                  isInvasive = false,
                  abundancePercent = 0.0,
              ),
          ),
          "Table before deletion")

      deleteObservationBiomassDetails(observationId, plotId)

      assertTableEquals(
          listOf(
              ObservationBiomassQuadrantSpeciesRecord(
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  positionId = ObservationPlotPosition.NortheastCorner,
                  speciesId = speciesId1,
                  isThreatened = true,
                  isInvasive = false,
                  abundancePercent = 0.0,
              ),
          ),
          "Table after deletion")
    }
  }

  @Nested
  inner class ObservationBiomassAdditionalSpeciesTable {

    @Test
    fun `throws exception if inserting additional species without biomass details`() {
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassAdditionalSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            isThreatened = true)
      }
    }

    @Test
    fun `throws exception if species Id inserted twice`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassAdditionalSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          speciesId = speciesId1,
          isInvasive = true,
      )

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassAdditionalSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            isInvasive = true,
        )
      }
    }

    @Test
    fun `throws exception if same species name inserted twice`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassAdditionalSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          speciesName = "Other Species",
          isInvasive = true,
      )

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassAdditionalSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesName = "Other Species",
            isInvasive = true,
        )
      }
    }

    @Test
    fun `throws exception if both species ID and name are null`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassAdditionalSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesName = null,
            speciesId = null,
            isInvasive = true,
        )
      }
    }

    @Test
    fun `throws exception if species is neither invasive nor threatened`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      assertThrows<DataIntegrityViolationException> {
        insertObservationBiomassAdditionalSpecies(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            isInvasive = false,
            isThreatened = false,
        )
      }
    }

    @Test
    fun `deletes associated additional species if biomass details row is deleted`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertObservationBiomassAdditionalSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          speciesId = speciesId1,
          isThreatened = true,
      )

      insertObservationBiomassAdditionalSpecies(
          observationId = observationId,
          monitoringPlotId = plotId,
          speciesId = speciesId2,
          isInvasive = true,
      )

      val otherObservationId =
          insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
      val otherPlotId = insertMonitoringPlot(isAdHoc = true)
      insertObservationPlot(observationId = otherObservationId, monitoringPlotId = otherPlotId)
      insertObservationBiomassDetails(
          observationId = otherObservationId, monitoringPlotId = otherPlotId)
      insertObservationBiomassAdditionalSpecies(
          observationId = otherObservationId,
          monitoringPlotId = otherPlotId,
          speciesId = speciesId2,
          isInvasive = true,
      )

      assertTableEquals(
          listOf(
              ObservationBiomassAdditionalSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = speciesId1,
                  isThreatened = true,
                  isInvasive = false,
              ),
              ObservationBiomassAdditionalSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = speciesId2,
                  isThreatened = false,
                  isInvasive = true,
              ),
              ObservationBiomassAdditionalSpeciesRecord(
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  speciesId = speciesId2,
                  isThreatened = false,
                  isInvasive = true,
              ),
          ),
          "Table before deletion")

      deleteObservationBiomassDetails(observationId, plotId)

      assertTableEquals(
          listOf(
              ObservationBiomassAdditionalSpeciesRecord(
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  speciesId = speciesId2,
                  isThreatened = false,
                  isInvasive = true,
              ),
          ),
          "Table after deletion")
    }
  }

  @Nested
  inner class RecordedTreesTable {
    @Test
    fun `throws exception if recording trees without biomass details`() {
      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
        )
      }
    }

    @Test
    fun `throws exception for repeating tree number`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      insertRecordedTree(
          observationId = observationId,
          monitoringPlotId = plotId,
          speciesId = speciesId1,
          treeNumber = 1,
          treeGrowthForm = TreeGrowthForm.Shrubs,
          shrubDiameterCm = 1.0)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeNumber = 1,
            treeGrowthForm = TreeGrowthForm.Shrubs,
            shrubDiameterCm = 1.0)
      }
    }

    @Test
    fun `throws exception if both species ID and species name are null`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = null,
            speciesName = null)
      }
    }

    @Test
    fun `throws exception if tree data are not-null for shrubs`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Shrubs,
            isTrunk = true,
            diameterAtBreastHeightCm = 1.0,
            pointOfMeasurementM = 1.3,
            shrubDiameterCm = 1.0,
        )
      }
    }

    @Test
    fun `throws exception if shrub diameter is null for shrubs`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Shrubs,
            shrubDiameterCm = null,
        )
      }
    }

    @Test
    fun `throws exception if shrub diameter is negative for shrubs`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Shrubs,
            shrubDiameterCm = -1.0,
        )
      }
    }

    @Test
    fun `throws exception if shrub diameter is not null for trees`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = true,
            diameterAtBreastHeightCm = 1.0,
            pointOfMeasurementM = 1.3,
            heightM = 1.0,
            shrubDiameterCm = 1.0,
        )
      }
    }

    @Test
    fun `throws exception if isTrunk is null for trees`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = null,
            diameterAtBreastHeightCm = 1.0,
            pointOfMeasurementM = 1.3,
            heightM = 1.0,
        )
      }
    }

    @Test
    fun `throws exception if diameter at breast height is negative for trees`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = true,
            diameterAtBreastHeightCm = -1.0,
            pointOfMeasurementM = 1.3,
            heightM = 1.0,
        )
      }
    }

    @Test
    fun `throws exception if diameter at breast height is null for trees`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = true,
            diameterAtBreastHeightCm = null,
            pointOfMeasurementM = 1.3,
            heightM = 1.0,
        )
      }
    }

    @Test
    fun `throws exception if point of measurement is negative for trees`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = true,
            diameterAtBreastHeightCm = 1.0,
            pointOfMeasurementM = -1.0,
            heightM = 1.0,
        )
      }
    }

    @Test
    fun `throws exception if point of measurement is null for trees`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = true,
            diameterAtBreastHeightCm = 1.0,
            pointOfMeasurementM = null,
            heightM = 1.0,
        )
      }
    }

    @Test
    fun `throws exception if height is negative for trees`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = true,
            diameterAtBreastHeightCm = 1.0,
            pointOfMeasurementM = 1.3,
            heightM = -1.0,
        )
      }
    }

    @Test
    fun `throws exception if height is null when diameter at breast height is above 5`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)

      assertThrows<DataIntegrityViolationException> {
        insertRecordedTree(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            treeGrowthForm = TreeGrowthForm.Trees,
            isTrunk = true,
            diameterAtBreastHeightCm = 5.1,
            pointOfMeasurementM = 1.3,
            heightM = null,
        )
      }
    }

    @Test
    fun `deletes associated recorded trees if biomass details row is deleted`() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      val treeId1 =
          insertRecordedTree(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = speciesId1,
              treeGrowthForm = TreeGrowthForm.Shrubs,
              isDead = true,
              shrubDiameterCm = 1.0,
          )

      val treeId2 =
          insertRecordedTree(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = speciesId2,
              treeGrowthForm = TreeGrowthForm.Trees,
              isDead = false,
              isTrunk = true,
              diameterAtBreastHeightCm = 10.0,
              pointOfMeasurementM = 1.3,
              heightM = 1.5,
          )

      val otherObservationId =
          insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
      val otherPlotId = insertMonitoringPlot(isAdHoc = true)
      insertObservationPlot(observationId = otherObservationId, monitoringPlotId = otherPlotId)
      insertObservationBiomassDetails(
          observationId = otherObservationId, monitoringPlotId = otherPlotId)

      val otherTreeId =
          insertRecordedTree(
              observationId = otherObservationId,
              monitoringPlotId = otherPlotId,
              speciesId = speciesId1,
              treeGrowthForm = TreeGrowthForm.Shrubs,
              isDead = false,
              shrubDiameterCm = 1.0,
          )

      assertTableEquals(
          listOf(
              RecordedTreesRecord(
                  id = treeId1,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = speciesId1,
                  treeGrowthFormId = TreeGrowthForm.Shrubs,
                  treeNumber = 1L,
                  isDead = true,
                  shrubDiameterCm = 1.0,
              ),
              RecordedTreesRecord(
                  id = treeId2,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = speciesId2,
                  treeGrowthFormId = TreeGrowthForm.Trees,
                  treeNumber = 2L,
                  isDead = false,
                  isTrunk = true,
                  diameterAtBreastHeightCm = 10.0,
                  pointOfMeasurementM = 1.3,
                  heightM = 1.5,
              ),
              RecordedTreesRecord(
                  id = otherTreeId,
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  speciesId = speciesId1,
                  treeGrowthFormId = TreeGrowthForm.Shrubs,
                  treeNumber = 1L,
                  isDead = false,
                  shrubDiameterCm = 1.0,
              ),
          ),
          "Table before deletion")

      deleteObservationBiomassDetails(observationId, plotId)

      assertTableEquals(
          listOf(
              RecordedTreesRecord(
                  id = otherTreeId,
                  observationId = otherObservationId,
                  monitoringPlotId = otherPlotId,
                  speciesId = speciesId1,
                  treeGrowthFormId = TreeGrowthForm.Shrubs,
                  treeNumber = 1L,
                  isDead = false,
                  shrubDiameterCm = 1.0,
              ),
          ),
          "Table after deletion")
    }
  }

  @Nested
  inner class RecordedBranchTable {
    private lateinit var treeId: RecordedTreeId

    @BeforeEach
    fun setup() {
      insertObservationBiomassDetails(observationId = observationId, monitoringPlotId = plotId)
      treeId =
          insertRecordedTree(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = speciesId1,
              treeGrowthForm = TreeGrowthForm.Trees,
              isDead = false,
              isTrunk = true,
              diameterAtBreastHeightCm = 10.0,
              pointOfMeasurementM = 1.3,
              heightM = 1.5,
          )
    }

    @Test
    fun `throws exception for repeating branch number`() {
      insertRecordedBranch(
          treeId = treeId,
          branchNumber = 1L,
      )

      assertThrows<DataIntegrityViolationException> {
        insertRecordedBranch(
            treeId = treeId,
            branchNumber = 1L,
        )
      }
    }

    @Test
    fun `throws exception negative diameter at breast height`() {
      assertThrows<DataIntegrityViolationException> {
        insertRecordedBranch(
            treeId = treeId,
            diameterAtBreastHeightCm = -1.0,
        )
      }
    }

    @Test
    fun `throws exception negative point of measurement`() {
      assertThrows<DataIntegrityViolationException> {
        insertRecordedBranch(
            treeId = treeId,
            pointOfMeasurementM = -1.0,
        )
      }
    }

    @Test
    fun `deletes associated recorded branches if parent recorded tree row is deleted`() {
      val branchId1 =
          insertRecordedBranch(
              treeId = treeId,
              diameterAtBreastHeightCm = 3.0,
          )
      val branchId2 =
          insertRecordedBranch(
              treeId = treeId,
              diameterAtBreastHeightCm = 5.0,
              isDead = true,
          )

      val otherTreeId =
          insertRecordedTree(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = speciesId1,
              treeGrowthForm = TreeGrowthForm.Trees,
              isDead = false,
              isTrunk = true,
              diameterAtBreastHeightCm = 10.0,
              pointOfMeasurementM = 1.3,
              heightM = 1.5,
          )
      val otherBranchId =
          insertRecordedBranch(
              treeId = otherTreeId,
              diameterAtBreastHeightCm = 5.0,
              isDead = false,
          )

      assertTableEquals(
          listOf(
              RecordedBranchesRecord(
                  id = branchId1,
                  treeId = treeId,
                  branchNumber = 1L,
                  isDead = false,
                  diameterAtBreastHeightCm = 3.0,
                  pointOfMeasurementM = 1.3,
              ),
              RecordedBranchesRecord(
                  id = branchId2,
                  treeId = treeId,
                  branchNumber = 2L,
                  isDead = true,
                  diameterAtBreastHeightCm = 5.0,
                  pointOfMeasurementM = 1.3,
              ),
              RecordedBranchesRecord(
                  id = otherBranchId,
                  treeId = otherTreeId,
                  branchNumber = 1L,
                  isDead = false,
                  diameterAtBreastHeightCm = 5.0,
                  pointOfMeasurementM = 1.3,
              ),
          ),
          "Table before deletion")

      deleteRecordedTree(treeId)

      assertTableEquals(
          listOf(
              RecordedBranchesRecord(
                  id = otherBranchId,
                  treeId = otherTreeId,
                  branchNumber = 1L,
                  isDead = false,
                  diameterAtBreastHeightCm = 5.0,
                  pointOfMeasurementM = 1.3,
              ),
          ),
          "Table after deletion")
    }
  }
}
