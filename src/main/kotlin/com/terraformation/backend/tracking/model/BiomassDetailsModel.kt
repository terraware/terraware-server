package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import java.math.BigDecimal
import java.time.Instant

data class BiomassSpeciesKey(
    val speciesId: SpeciesId? = null,
    val scientificName: String? = null,
)

data class BiomassSpeciesModel(
    val commonName: String? = null,
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    val speciesId: SpeciesId? = null,
    val scientificName: String? = null,
)

data class BiomassQuadratSpeciesModel(
    val abundancePercent: Int,
    val speciesId: SpeciesId? = null,
    val speciesName: String? = null,
)

data class BiomassQuadratModel(
    val description: String? = null,
    val species: Set<BiomassQuadratSpeciesModel>,
)

data class RecordedTreeModel<TreeId : RecordedTreeId?>(
    val id: TreeId,
    val description: String? = null,
    val diameterAtBreastHeightCm: BigDecimal? = null,
    val heightM: BigDecimal? = null,
    val isDead: Boolean,
    val pointOfMeasurementM: BigDecimal? = null,
    val shrubDiameterCm: Int? = null,
    val speciesId: SpeciesId? = null,
    val speciesName: String? = null,
    val treeGrowthForm: TreeGrowthForm,
    val treeNumber: Int,
    val trunkNumber: Int,
) {
  fun validate() {
    if (speciesId == null && speciesName == null) {
      throw IllegalStateException("Tree $treeNumber: speciesId or speciesName missing")
    }
    when (treeGrowthForm) {
      TreeGrowthForm.Shrub -> {
        if (shrubDiameterCm == null) {
          throw IllegalStateException("Tree $treeNumber: shrubDiameter missing for Shrub")
        }
        if (trunkNumber != 1) {
          throw IllegalStateException("Tree $treeNumber: Trunk number must be 1 for Shrub")
        }
      }
      TreeGrowthForm.Tree -> {
        if (diameterAtBreastHeightCm == null) {
          throw IllegalStateException("Tree $treeNumber: diameterAtBreastHeight missing for Tree")
        }
        if (pointOfMeasurementM == null) {
          throw IllegalStateException("Tree $treeNumber: pointOfMeasurement missing for Tree")
        }
        if (heightM == null) {
          throw IllegalStateException("Tree $treeNumber: height missing for Tree")
        }
        if (trunkNumber != 1) {
          throw IllegalStateException("Tree $treeNumber: Trunk number must be 1 for Tree")
        }
      }
      TreeGrowthForm.Trunk -> {
        if (diameterAtBreastHeightCm == null) {
          throw IllegalStateException("Tree $treeNumber: diameterAtBreastHeight missing for Trunk")
        }
        if (pointOfMeasurementM == null) {
          throw IllegalStateException("Tree $treeNumber: pointOfMeasurement missing for Trunk")
        }
      }
    }
  }
}

typealias ExistingRecordedTreeModel = RecordedTreeModel<RecordedTreeId>

typealias NewRecordedTreeModel = RecordedTreeModel<Nothing?>

data class BiomassDetailsModel<
    ID : ObservationId?, PlotId : MonitoringPlotId?, TreeId : RecordedTreeId?>(
    val description: String? = null,
    val forestType: BiomassForestType,
    val herbaceousCoverPercent: Int,
    val observationId: ID,
    val ph: BigDecimal? = null,
    val quadrats: Map<ObservationPlotPosition, BiomassQuadratModel> = emptyMap(),
    val salinityPpt: BigDecimal? = null,
    val smallTreeCountRange: Pair<Int, Int>,
    val soilAssessment: String,
    val species: Set<BiomassSpeciesModel> = emptySet(),
    val plotId: PlotId,
    val tide: MangroveTide? = null,
    val tideTime: Instant? = null,
    val trees: List<RecordedTreeModel<TreeId>> = emptyList(),
    val waterDepthCm: Int? = null,
) {
  fun validate() {
    if (forestType == BiomassForestType.Mangrove) {
      if (ph == null) {
        throw IllegalStateException("ph required for Mangrove")
      }
      if (salinityPpt == null) {
        throw IllegalStateException("salinityPpt required for Mangrove")
      }
      if (tide == null) {
        throw IllegalStateException("tide required for Mangrove")
      }
      if (tideTime == null) {
        throw IllegalStateException("tideTime required for Mangrove")
      }
      if (waterDepthCm == null) {
        throw IllegalStateException("waterDepth required for Mangrove")
      }
    }

    // Map of tree number to list of trunk numbers
    val treesByNumbers = trees.groupBy { it.treeNumber }

    treesByNumbers.forEach { (treeNumber, trees) ->
      val trunkNumbers = trees.map { it.trunkNumber }
      if (trunkNumbers.distinct() != trunkNumbers) {
        throw IllegalStateException("Tree $treeNumber contains duplicates")
      }

      if (trees.size > 1) {
        if (!trees.all { it.treeGrowthForm == trees[0].treeGrowthForm }) {
          throw IllegalStateException("Tree $treeNumber consists of multiple growth forms")
        }

        if (trees[0].treeGrowthForm != TreeGrowthForm.Trunk) {
          throw IllegalStateException(
              "Tree $treeNumber consists of multiple trunks, but does not have growth form Trunk")
        }

        if (!trees.all {
          it.speciesName == trees[0].speciesName && it.speciesId == trees[0].speciesId
        }) {
          throw IllegalStateException("Tree $treeNumber consists of multiple species")
        }
      }
    }

    trees.forEach { it.validate() }
  }
}

typealias ExistingBiomassDetailsModel =
    BiomassDetailsModel<ObservationId, MonitoringPlotId, RecordedTreeId>

typealias NewBiomassDetailsModel = BiomassDetailsModel<Nothing?, Nothing?, Nothing?>
