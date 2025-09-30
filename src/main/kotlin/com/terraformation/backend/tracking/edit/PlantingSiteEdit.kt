package com.terraformation.backend.tracking.edit

import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal

/**
 * Represents the changes that need to be made to an existing planting site to make it match an
 * updated version supplied by the user. The emphasis here is on changes to the site's structure;
 * simple changes like edits to the site name aren't included.
 */
data class PlantingSiteEdit(
    /**
     * Difference in usable area between the old version of the site (if any) and the new one. A
     * positive value means the site has grown; a negative value means it has shrunk. Note that it
     * is possible for a site to gain area in some places and lose it in others; this value is the
     * net difference when all those changes are added up.
     */
    val areaHaDifference: BigDecimal,

    /** Desired planting site model. The intended end result after edits are applied. */
    val desiredModel: AnyPlantingSiteModel,

    /** Existing planting site model. Edits are based on this version of the site. */
    val existingModel: ExistingPlantingSiteModel,

    /** Edits to this site's planting zones. */
    val plantingZoneEdits: List<PlantingZoneEdit>,
) {
  fun equalsExact(other: PlantingSiteEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          desiredModel.equals(other.desiredModel, tolerance) &&
          existingModel.equals(other.existingModel, tolerance) &&
          plantingZoneEdits.size == other.plantingZoneEdits.size &&
          plantingZoneEdits.zip(other.plantingZoneEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          }
}
