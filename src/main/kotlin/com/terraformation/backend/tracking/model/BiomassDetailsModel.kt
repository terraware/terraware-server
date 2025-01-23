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
    val shrubDiameterCm: Int? = null,
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
    val trees: List<RecordedTreeModel<TreeId, BranchId>> = emptyList(),
    val waterDepthCm: Int? = null,
) {
  fun validate() {
    // TODO implement field validation according to acceptable ranges once finalized
    trees.forEach { it.validate() }
  }
}

typealias ExistingBiomassDetailsModel =
    BiomassDetailsModel<ObservationId, MonitoringPlotId, RecordedTreeId, RecordedBranchId>

typealias NewBiomassDetailsModel = BiomassDetailsModel<Nothing?, Nothing?, Nothing?, Nothing?>
