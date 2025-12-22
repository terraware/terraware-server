package com.terraformation.backend.tracking.edit

import com.terraformation.backend.tracking.model.AnyStratumModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

/**
 * Represents the changes that need to be made to a stratum to make it match the strata in an
 * updated version of a planting site. An updated planting site can have new strata, can be missing
 * existing strata, or can have changes to existing strata; these are modeled as create, delete, and
 * update operations.
 */
sealed interface StratumEdit {
  /**
   * Usable region that is being added to this stratum. Does not include any areas that are covered
   * by the updated site's exclusion areas.
   */
  val addedRegion: MultiPolygon?

  /**
   * Difference in usable area between the old version of the stratum (if any) and the new one. A
   * positive value means the stratum has grown or is being created; a negative value means it has
   * shrunk or is being deleted. Note that it is possible for a stratum to gain area in some places
   * and lose it in others; this value is the net difference when all those changes are added up.
   */
  val areaHaDifference: BigDecimal

  /** Desired stratum model, or null if the stratum is being removed. */
  val desiredModel: AnyStratumModel?

  /** Existing stratum model, or null if the stratum is being created. */
  val existingModel: ExistingStratumModel?

  /**
   * New monitoring plots that need to be created in this stratum as a result of its boundary or
   * settings having changed.
   */
  val monitoringPlotEdits: List<MonitoringPlotEdit.Create>

  /** Edits to this stratum's substrata. */
  val substratumEdits: List<SubstratumEdit>

  /**
   * Usable region that is being removed from this stratum. Does not include any areas that are
   * covered by the existing site's exclusion areas.
   */
  val removedRegion: MultiPolygon?

  fun equalsExact(other: StratumEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          addedRegion.equalsOrBothNull(other.addedRegion, tolerance) &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          desiredModel == other.desiredModel &&
          existingModel == other.existingModel &&
          monitoringPlotEdits.size == other.monitoringPlotEdits.size &&
          monitoringPlotEdits.zip(other.monitoringPlotEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          } &&
          substratumEdits.size == other.substratumEdits.size &&
          substratumEdits.zip(other.substratumEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          } &&
          removedRegion.equalsOrBothNull(other.removedRegion, tolerance)

  data class Create(
      override val desiredModel: AnyStratumModel,
      override val monitoringPlotEdits: List<MonitoringPlotEdit.Create>,
      override val substratumEdits: List<SubstratumEdit>,
  ) : StratumEdit {
    override val addedRegion: MultiPolygon
      get() = desiredModel.boundary

    override val areaHaDifference: BigDecimal
      get() = desiredModel.areaHa

    override val existingModel: ExistingStratumModel?
      get() = null

    override val removedRegion: MultiPolygon?
      get() = null
  }

  data class Delete(
      override val existingModel: ExistingStratumModel,
      override val substratumEdits: List<SubstratumEdit.Delete>,
  ) : StratumEdit {
    override val areaHaDifference: BigDecimal
      get() = existingModel.areaHa.negate()

    override val addedRegion: MultiPolygon?
      get() = null

    override val desiredModel: AnyStratumModel?
      get() = null

    override val monitoringPlotEdits: List<MonitoringPlotEdit.Create>
      get() = emptyList()

    override val removedRegion: MultiPolygon
      get() = existingModel.boundary
  }

  data class Update(
      override val addedRegion: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val desiredModel: AnyStratumModel,
      override val existingModel: ExistingStratumModel,
      override val monitoringPlotEdits: List<MonitoringPlotEdit.Create>,
      override val substratumEdits: List<SubstratumEdit>,
      override val removedRegion: MultiPolygon,
  ) : StratumEdit
}
