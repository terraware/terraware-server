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
    val speciesId: SpeciesId? = null,
    val speciesName: String? = null,
)

data class BiomassQuadratSpeciesModel(
    val abundancePercent: BigDecimal,
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    val speciesId: SpeciesId? = null,
    val speciesName: String? = null,
)

data class BiomassQuadratModel(
    val description: String? = null,
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
    val branches: List<RecordedBranchModel<BranchId>> = emptyList(),
    val description: String? = null,
    val diameterAtBreastHeightCm: BigDecimal? = null,
    val heightM: BigDecimal? = null,
    val isDead: Boolean,
    val isTrunk: Boolean? = null,
    val pointOfMeasurementM: BigDecimal? = null,
    val shrubDiameterCm: BigDecimal? = null,
    val speciesId: SpeciesId? = null,
    val speciesName: String? = null,
    val treeGrowthForm: TreeGrowthForm,
    val treeNumber: Int,
) {
  fun validate() {
    if (isTrunk == true && branches.isEmpty()) {
      throw IllegalStateException("Tree trunk must contain at least one branch.")
    }

    if (isTrunk != true && branches.isNotEmpty()) {
      throw IllegalStateException("Only tree trunk can contain branches.")
    }
  }
}

typealias ExistingRecordedTreeModel = RecordedTreeModel<RecordedTreeId, RecordedBranchId>

typealias NewRecordedTreeModel = RecordedTreeModel<Nothing?, Nothing?>

data class BiomassDetailsModel<
    ID : ObservationId?,
    PlotId : MonitoringPlotId?,
    TreeId : RecordedTreeId?,
    BranchId : RecordedBranchId?>(
    val additionalSpecies: List<BiomassAdditionalSpeciesModel>,
    val description: String? = null,
    val forestType: BiomassForestType,
    val herbaceousCoverPercent: BigDecimal,
    val observationId: ID,
    val ph: BigDecimal? = null,
    val quadrats: Map<ObservationPlotPosition, BiomassQuadratModel>,
    val salinityPpt: BigDecimal? = null,
    val smallTreeCountRange: Pair<Int, Int>,
    val soilAssessment: String? = null,
    val plotId: PlotId,
    val tide: MangroveTide? = null,
    val tideTime: Instant? = null,
    val trees: List<RecordedTreeModel<TreeId, BranchId>>,
    val waterDepthCm: BigDecimal? = null,
) {
  fun validate() {
    // TODO convert database validation errors to human-readable error messages
    trees.forEach { it.validate() }
  }
}

typealias ExistingBiomassDetailsModel =
    BiomassDetailsModel<ObservationId, MonitoringPlotId, RecordedTreeId, RecordedBranchId>

typealias NewBiomassDetailsModel = BiomassDetailsModel<Nothing?, Nothing?, Nothing?, Nothing?>
