package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

/**
 * Represents the changes that need to be made to an existing planting site to make it match an
 * updated version supplied by the user. The emphasis here is on changes to the site's structure;
 * simple changes like edits to the site name aren't included.
 *
 * This may include a list of [problems] encountered while calculating the differences between the
 * two versions of the site. If so, the edit should be considered invalid and the changes shouldn't
 * be applied to the site.
 */
data class PlantingSiteEdit(
    /**
     * Difference in usable area between the old version of the site (if any) and the new one. A
     * positive value means the site has grown; a negative value means it has shrunk. Note that it
     * is possible for a site to gain area in some places and lose it in others; this value is the
     * net difference when all those changes are added up.
     */
    val areaHaDifference: BigDecimal,

    /** New site boundary. May intersect with [exclusion]. */
    val boundary: MultiPolygon,

    /** New site exclusion areas, if any. */
    val exclusion: MultiPolygon?,

    /** ID of existing site. */
    val plantingSiteId: PlantingSiteId,

    /** Edits to this site's planting zones. */
    val plantingZoneEdits: List<PlantingZoneEdit>,

    /**
     * List of problems that prevent the edit from being performed. If this is nonempty, the edit
     * should be considered invalid.
     */
    val problems: List<PlantingSiteEditProblem> = emptyList(),
) {
  fun equalsExact(other: PlantingSiteEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          areaHaDifference.equalsIgnoreScale(other.areaHaDifference) &&
          boundary.equalsOrBothNull(other.boundary, tolerance) &&
          exclusion.equalsOrBothNull(other.exclusion, tolerance) &&
          plantingSiteId == other.plantingSiteId &&
          plantingZoneEdits.size == other.plantingZoneEdits.size &&
          plantingZoneEdits.zip(other.plantingZoneEdits).all { (edit, otherEdit) ->
            edit.equalsExact(otherEdit, tolerance)
          } &&
          problems == other.problems
}
