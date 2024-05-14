package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingZoneId
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
interface PlantingZoneEdit {
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

  /** New zone boundary. May intersect with the updated site's exclusion areas. */
  val boundary: MultiPolygon

  /**
   * IDs of existing monitoring plots that are no longer contained in the zone's usable area and
   * should be removed.
   */
  val monitoringPlotsRemoved: Set<MonitoringPlotId>

  /** New zone name, or null if the zone is being removed. May be the same as the old name. */
  val newName: String?

  /**
   * Number of permanent clusters to add to the zone. These clusters must all be located in
   * [addedRegion].
   */
  val numPermanentClustersToAdd: Int?

  /** Old zone name, or null if the zone is being newly created. */
  val oldName: String?

  /** Edits to this zone's subzones. */
  val plantingSubzoneEdits: List<PlantingSubzoneEdit>

  /** The zone's ID if it already exists, or null if it is being newly created. */
  val plantingZoneId: PlantingZoneId?

  /**
   * Usable region that is being removed from this zone. Does not include any areas that are covered
   * by the existing site's exclusion areas.
   */
  val removedRegion: MultiPolygon?

  fun equalsExact(other: PlantingZoneEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          addedRegion.equalsOrBothNull(other.addedRegion, tolerance) &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          boundary.equalsOrBothNull(other.boundary, tolerance) &&
          monitoringPlotsRemoved == other.monitoringPlotsRemoved &&
          newName == other.newName &&
          numPermanentClustersToAdd == other.numPermanentClustersToAdd &&
          oldName == other.oldName &&
          plantingSubzoneEdits.size == other.plantingSubzoneEdits.size &&
          plantingSubzoneEdits.zip(other.plantingSubzoneEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          } &&
          plantingZoneId == other.plantingZoneId &&
          removedRegion.equalsOrBothNull(other.removedRegion, tolerance)

  data class Create(
      override val addedRegion: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val boundary: MultiPolygon,
      override val newName: String,
      override val numPermanentClustersToAdd: Int,
      override val plantingSubzoneEdits: List<PlantingSubzoneEdit.Create>,
  ) : PlantingZoneEdit {
    override val monitoringPlotsRemoved: Set<MonitoringPlotId>
      get() = emptySet()

    override val oldName: String?
      get() = null

    override val plantingZoneId: PlantingZoneId?
      get() = null

    override val removedRegion: MultiPolygon?
      get() = null
  }

  data class Delete(
      override val areaHaDifference: BigDecimal,
      override val boundary: MultiPolygon,
      override val monitoringPlotsRemoved: Set<MonitoringPlotId>,
      override val oldName: String,
      override val plantingZoneId: PlantingZoneId,
      override val plantingSubzoneEdits: List<PlantingSubzoneEdit.Delete>,
      override val removedRegion: MultiPolygon,
  ) : PlantingZoneEdit {
    override val addedRegion: MultiPolygon?
      get() = null

    override val newName: String?
      get() = null

    override val numPermanentClustersToAdd: Int
      get() = 0
  }

  data class Update(
      override val addedRegion: MultiPolygon,
      override val areaHaDifference: BigDecimal,
      override val boundary: MultiPolygon,
      override val monitoringPlotsRemoved: Set<MonitoringPlotId>,
      override val newName: String,
      override val numPermanentClustersToAdd: Int,
      override val oldName: String,
      override val plantingSubzoneEdits: List<PlantingSubzoneEdit>,
      override val plantingZoneId: PlantingZoneId,
      override val removedRegion: MultiPolygon,
  ) : PlantingZoneEdit
}
