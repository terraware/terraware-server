package com.terraformation.backend.tracking.edit

import com.terraformation.backend.tracking.model.AnySubstratumModel
import com.terraformation.backend.tracking.model.ExistingSubstratumModel
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

/**
 * Represents the changes that need to be made to a substratum to make it match the substrata in an
 * updated version of a planting site. An updated planting site can have new substrata (either in
 * existing strata or in newly-added strata), can be missing existing substrata, or can have changes
 * to existing substrata; these are modeled as create, delete, and update operations.
 */
sealed interface SubstratumEdit {
  /**
   * Usable region that is being added to this substratum. Does not include any areas that are
   * covered by the updated site's exclusion areas.
   */
  val addedRegion: MultiPolygon?

  /**
   * Difference in usable area between the old version of the substratum (if any) and the new one. A
   * positive value means the substratum has grown or is being created; a negative value means it
   * has shrunk or is being deleted. Note that it is possible for a substratum to gain area in some
   * places and lose it in others; this value is the net difference when all those changes are added
   * up.
   */
  val areaHaDifference: BigDecimal

  /** Desired substratum model, or null if the substratum is being removed. */
  val desiredModel: AnySubstratumModel?

  /** Existing substratum model, or null if the substratum is being created. */
  val existingModel: ExistingSubstratumModel?

  /**
   * Changes to make to this substratum's monitoring plots. The mix of monitoring plot edit types
   * depends on what change is being made to the substratum; see [MonitoringPlotEdit] for details.
   */
  val monitoringPlotEdits: List<MonitoringPlotEdit>

  /**
   * Usable region that is being removed from this substratum. Does not include any areas that are
   * covered by the existing site's exclusion areas.
   */
  val removedRegion: MultiPolygon?

  fun equalsExact(other: SubstratumEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          addedRegion.equalsOrBothNull(other.addedRegion, tolerance) &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          desiredModel == other.desiredModel &&
          existingModel == other.existingModel &&
          monitoringPlotEdits.size == other.monitoringPlotEdits.size &&
          monitoringPlotEdits.zip(other.monitoringPlotEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          } &&
          removedRegion.equalsOrBothNull(other.removedRegion, tolerance)

  data class Create(
      override val desiredModel: AnySubstratumModel,
      override val monitoringPlotEdits: List<MonitoringPlotEdit> = emptyList(),
  ) : SubstratumEdit {
    override val addedRegion: MultiPolygon
      get() = desiredModel.boundary

    override val areaHaDifference: BigDecimal
      get() = desiredModel.areaHa

    override val existingModel: ExistingSubstratumModel?
      get() = null

    override val removedRegion: MultiPolygon?
      get() = null
  }

  data class Delete(
      override val existingModel: ExistingSubstratumModel,
      override val monitoringPlotEdits: List<MonitoringPlotEdit> = emptyList(),
  ) : SubstratumEdit {
    override val addedRegion: MultiPolygon?
      get() = null

    override val areaHaDifference: BigDecimal
      get() = existingModel.areaHa.negate()

    override val desiredModel: AnySubstratumModel?
      get() = null

    override val removedRegion: MultiPolygon
      get() = existingModel.boundary
  }

  data class Update(
      override val addedRegion: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val desiredModel: AnySubstratumModel,
      override val existingModel: ExistingSubstratumModel,
      override val monitoringPlotEdits: List<MonitoringPlotEdit> = emptyList(),
      override val removedRegion: MultiPolygon,
  ) : SubstratumEdit
}
