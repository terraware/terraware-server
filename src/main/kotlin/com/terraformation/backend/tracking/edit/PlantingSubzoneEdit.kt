package com.terraformation.backend.tracking.edit

import com.terraformation.backend.tracking.model.AnyPlantingSubzoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingSubzoneModel
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

/**
 * Represents the changes that need to be made to a planting subzone to make it match the subzones
 * in an updated version of a planting site. An updated planting site can have new subzones (either
 * in existing zones or in newly-added zones), can be missing existing subzones, or can have changes
 * to existing subzones; these are modeled as create, delete, and update operations.
 */
sealed interface PlantingSubzoneEdit {
  /**
   * Usable region that is being added to this subzone. Does not include any areas that are covered
   * by the updated site's exclusion areas.
   */
  val addedRegion: MultiPolygon?

  /**
   * Difference in usable area between the old version of the subzone (if any) and the new one. A
   * positive value means the subzone has grown or is being created; a negative value means it has
   * shrunk or is being deleted. Note that it is possible for a subzone to gain area in some places
   * and lose it in others; this value is the net difference when all those changes are added up.
   */
  val areaHaDifference: BigDecimal

  /** Desired subzone model, or null if the subzone is being removed. */
  val desiredModel: AnyPlantingSubzoneModel?

  /** Existing subzone model, or null if the subzone is being created. */
  val existingModel: ExistingPlantingSubzoneModel?

  /**
   * Usable region that is being removed from this subzone. Does not include any areas that are
   * covered by the existing site's exclusion areas.
   */
  val removedRegion: MultiPolygon?

  fun equalsExact(other: PlantingSubzoneEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          addedRegion.equalsOrBothNull(other.addedRegion, tolerance) &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          desiredModel == other.desiredModel &&
          existingModel == other.existingModel &&
          removedRegion.equalsOrBothNull(other.removedRegion, tolerance)

  data class Create(
      override val desiredModel: AnyPlantingSubzoneModel,
  ) : PlantingSubzoneEdit {
    override val addedRegion: MultiPolygon
      get() = desiredModel.boundary

    override val areaHaDifference: BigDecimal
      get() = desiredModel.areaHa

    override val existingModel: ExistingPlantingSubzoneModel?
      get() = null

    override val removedRegion: MultiPolygon?
      get() = null
  }

  data class Delete(
      override val existingModel: ExistingPlantingSubzoneModel,
  ) : PlantingSubzoneEdit {
    override val addedRegion: MultiPolygon?
      get() = null

    override val areaHaDifference: BigDecimal
      get() = existingModel.areaHa.negate()

    override val desiredModel: AnyPlantingSubzoneModel?
      get() = null

    override val removedRegion: MultiPolygon
      get() = existingModel.boundary
  }

  data class Update(
      override val addedRegion: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val desiredModel: AnyPlantingSubzoneModel,
      override val existingModel: ExistingPlantingSubzoneModel,
      override val removedRegion: MultiPolygon,
  ) : PlantingSubzoneEdit
}
