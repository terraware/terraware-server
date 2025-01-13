package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.RecordedBranchId
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import java.math.BigDecimal
import java.time.Instant

data class BiomassAdditionalSpeciesModel(
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    val speciesId: SpeciesId?,
    val speciesName: String?,
)

data class BiomassQuadratSpeciesModel(
    val abundancePercent: BigDecimal,
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    val speciesId: SpeciesId?,
    val speciesName: String?,
)

data class BiomassQuadratModel(
    val description: String?,
    val position: ObservationPlotPosition,
    val species: List<BiomassQuadratSpeciesModel>,
)

data class RecordedBranchModel<ID : RecordedBranchId?>(
    val id: ID,
    val branchNumber: Int,
    val description: String?,
    val diameterAtBreastHeightCm: BigDecimal,
    val isDead: Boolean,
    val pointOfMeasurementM: BigDecimal,
)

typealias ExistingRecordedBranchModel = RecordedBranchModel<RecordedBranchId>

typealias NewRecordedBranchModel = RecordedBranchModel<Nothing?>

data class RecordedTreeModel<TreeId : RecordedTreeId?, BranchId : RecordedBranchId?>(
    val id: TreeId,
    val branches: List<RecordedBranchModel<BranchId>>,
    val description: String?,
    val diameterAtBreastHeightCm: BigDecimal?,
    val heightM: BigDecimal?,
    val isDead: Boolean,
    val isTrunk: Boolean?,
    val pointOfMeasurementM: BigDecimal?,
    val shrubDiameterCm: BigDecimal?,
    val speciesId: SpeciesId?,
    val speciesName: String?,
    val treeGrowthForm: TreeGrowthForm,
    val treeNumber: Int,
) {
  fun validate() {
    val shouldHaveBranches = treeGrowthForm != TreeGrowthForm.Tree && isTrunk != true

    if (shouldHaveBranches && branches.isEmpty()) {
      throw IllegalStateException("Tree trunk must contain at least one branch.")
    }

    if (!shouldHaveBranches && branches.isNotEmpty()) {
      throw IllegalStateException("Only tree trunk can contain branches.")
    }
  }
}

typealias ExistingRecordedTreeModel = RecordedTreeModel<RecordedTreeId, RecordedBranchId>

typealias NewRecordedTreeModel = RecordedTreeModel<Nothing?, Nothing?>

data class BiomassDetailsModel<TreeId : RecordedTreeId?, BranchId : RecordedBranchId?>(
    val additionalSpecies: List<BiomassAdditionalSpeciesModel>,
    val description: String?,
    val forestType: BiomassForestType,
    val herbaceousCoverPercent: BigDecimal,
    val observationId: ObservationId,
    val ph: BigDecimal?,
    val quadrats: Map<ObservationPlotPosition, BiomassQuadratModel>,
    val salinityPpt: BigDecimal?,
    val smallTreeCountRange: Pair<Int, Int>,
    val soilAssessment: String?,
    val plotId: MonitoringPlotId,
    val tide: MangroveTide?,
    val tideTime: Instant?,
    val trees: List<RecordedTreeModel<TreeId, BranchId>>,
    val waterDepthCm: BigDecimal?,
) {
  fun validate() {
    // TODO convert database validation errors to human-readable error messages
    trees.forEach { it.validate() }
  }
}

typealias ExistingBiomassDetailsModel = BiomassDetailsModel<RecordedTreeId, RecordedBranchId>

typealias NewBiomassDetailsModel = BiomassDetailsModel<Nothing?, Nothing?>
