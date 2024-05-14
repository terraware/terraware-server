package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.PlantingSubzoneId
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
interface PlantingSubzoneEdit {
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

  /** New subzone boundary. May intersect with the updated site's exclusion areas. */
  val boundary: MultiPolygon

  /** New subzone name, or null if the subzone is being removed. May be the same as the old name. */
  val newName: String?

  /** Old subzone name, or null if the subzone is being newly created. */
  val oldName: String?

  /** The subzone's ID if it already exists, or null if it is being newly created. */
  val plantingSubzoneId: PlantingSubzoneId?

  /**
   * Usable region that is being removed from this subzone. Does not include any areas that are
   * covered by the existing site's exclusion areas.
   */
  val removedRegion: MultiPolygon?

  fun equalsExact(other: PlantingSubzoneEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          addedRegion.equalsOrBothNull(other.addedRegion, tolerance) &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          boundary.equalsOrBothNull(other.boundary, tolerance) &&
          newName == other.newName &&
          oldName == other.oldName &&
          plantingSubzoneId == other.plantingSubzoneId &&
          removedRegion.equalsOrBothNull(other.removedRegion, tolerance)

  data class Create(
      override val addedRegion: MultiPolygon,
      override val boundary: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val newName: String,
  ) : PlantingSubzoneEdit {
    override val oldName: String?
      get() = null

    override val plantingSubzoneId: PlantingSubzoneId?
      get() = null

    override val removedRegion: MultiPolygon?
      get() = null
  }

  data class Delete(
      override val areaHaDifference: BigDecimal,
      override val boundary: MultiPolygon,
      override val oldName: String,
      override val plantingSubzoneId: PlantingSubzoneId,
      override val removedRegion: MultiPolygon,
  ) : PlantingSubzoneEdit {
    override val addedRegion: MultiPolygon?
      get() = null

    override val newName: String?
      get() = null
  }

  data class Update(
      override val addedRegion: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val boundary: MultiPolygon,
      override val newName: String,
      override val oldName: String,
      override val plantingSubzoneId: PlantingSubzoneId,
      override val removedRegion: MultiPolygon,
  ) : PlantingSubzoneEdit
}
