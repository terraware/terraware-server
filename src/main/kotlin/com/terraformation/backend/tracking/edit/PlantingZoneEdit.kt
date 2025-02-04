package com.terraformation.backend.tracking.edit

import com.terraformation.backend.tracking.model.AnyPlantingZoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingZoneModel
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

/**
 * Represents the changes that need to be made to a planting zone to make it match the zones in an
 * updated version of a planting site. An updated planting site can have new zones, can be missing
 * existing zones, or can have changes to existing zones; these are modeled as create, delete, and
 * update operations.
 */
sealed interface PlantingZoneEdit {
  /**
   * Usable region that is being added to this zone. Does not include any areas that are covered by
   * the updated site's exclusion areas.
   */
  val addedRegion: MultiPolygon?

  /**
   * Difference in usable area between the old version of the zone (if any) and the new one. A
   * positive value means the zone has grown or is being created; a negative value means it has
   * shrunk or is being deleted. Note that it is possible for a zone to gain area in some places and
   * lose it in others; this value is the net difference when all those changes are added up.
   */
  val areaHaDifference: BigDecimal

  /** Desired zone model, or null if the zone is being removed. */
  val desiredModel: AnyPlantingZoneModel?

  /** Existing zone model, or null if the zone is being created. */
  val existingModel: ExistingPlantingZoneModel?

  /**
   * New monitoring plots that need to be created in this zone as a result of its boundary or
   * settings having changed.
   */
  val monitoringPlotEdits: List<MonitoringPlotEdit.Create>

  /** Edits to this zone's subzones. */
  val plantingSubzoneEdits: List<PlantingSubzoneEdit>

  /**
   * Usable region that is being removed from this zone. Does not include any areas that are covered
   * by the existing site's exclusion areas.
   */
  val removedRegion: MultiPolygon?

  fun equalsExact(other: PlantingZoneEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          addedRegion.equalsOrBothNull(other.addedRegion, tolerance) &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          desiredModel == other.desiredModel &&
          existingModel == other.existingModel &&
          monitoringPlotEdits.size == other.monitoringPlotEdits.size &&
          monitoringPlotEdits.zip(other.monitoringPlotEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          } &&
          plantingSubzoneEdits.size == other.plantingSubzoneEdits.size &&
          plantingSubzoneEdits.zip(other.plantingSubzoneEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          } &&
          removedRegion.equalsOrBothNull(other.removedRegion, tolerance)

  data class Create(
      override val desiredModel: AnyPlantingZoneModel,
      override val monitoringPlotEdits: List<MonitoringPlotEdit.Create>,
      override val plantingSubzoneEdits: List<PlantingSubzoneEdit.Create>,
  ) : PlantingZoneEdit {
    override val addedRegion: MultiPolygon
      get() = desiredModel.boundary

    override val areaHaDifference: BigDecimal
      get() = desiredModel.areaHa

    override val existingModel: ExistingPlantingZoneModel?
      get() = null

    override val removedRegion: MultiPolygon?
      get() = null
  }

  data class Delete(
      override val existingModel: ExistingPlantingZoneModel,
      override val plantingSubzoneEdits: List<PlantingSubzoneEdit.Delete>,
  ) : PlantingZoneEdit {
    override val areaHaDifference: BigDecimal
      get() = existingModel.areaHa.negate()

    override val addedRegion: MultiPolygon?
      get() = null

    override val desiredModel: AnyPlantingZoneModel?
      get() = null

    override val monitoringPlotEdits: List<MonitoringPlotEdit.Create>
      get() = emptyList()

    override val removedRegion: MultiPolygon
      get() = existingModel.boundary
  }

  data class Update(
      override val addedRegion: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val desiredModel: AnyPlantingZoneModel,
      override val existingModel: ExistingPlantingZoneModel,
      override val monitoringPlotEdits: List<MonitoringPlotEdit.Create>,
      override val plantingSubzoneEdits: List<PlantingSubzoneEdit>,
      override val removedRegion: MultiPolygon,
  ) : PlantingZoneEdit
}
