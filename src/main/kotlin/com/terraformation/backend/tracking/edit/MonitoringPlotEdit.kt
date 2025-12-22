package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.util.equalsOrBothNull
import org.locationtech.jts.geom.MultiPolygon

/**
 * Represents the changes that need to be made to a monitoring plot to make it match the strata and
 * substrata in an updated version of a planting site.
 */
sealed interface MonitoringPlotEdit {
  /**
   * If the edit is a change to an existing monitoring plot, its ID. Null if the edit is the
   * creation of a new plot.
   */
  val monitoringPlotId: MonitoringPlotId?

  /**
   * New permanent index to assign to the plot. Null if the plot should not have a permanent index
   * any more, or if the plot is being deleted.
   */
  val permanentIndex: Int?

  /** Region in which to create new plot. */
  val region: MultiPolygon?
    get() = null

  fun equalsExact(other: MonitoringPlotEdit, tolerance: Double = 0.0000001): Boolean =
      javaClass == other.javaClass &&
          monitoringPlotId == other.monitoringPlotId &&
          permanentIndex == other.permanentIndex &&
          region.equalsOrBothNull(other.region, tolerance)

  /**
   * Represents a monitoring plot that needs to move from one substratum to another, or change its
   * permanent index, because the substratum boundaries have changed out from under the plot.
   *
   * This will always be on the list of monitoring plot edits for the plot's _new_ substratum, never
   * on its existing substratum.
   *
   * The plot may have a different permanent index, or none at all, in its new home.
   */
  data class Adopt(
      override val monitoringPlotId: MonitoringPlotId,
      override val permanentIndex: Int? = null,
  ) : MonitoringPlotEdit

  /**
   * Represents a monitoring plot that needs to be created in a specific region. This can happen
   * when the containing stratum covers area that wasn't covered by the previous version of the
   * planting site.
   *
   * This always creates permanent plots. If [permanentIndex] is null, an index is chosen at random.
   */
  data class Create(
      override val region: MultiPolygon,
      override val permanentIndex: Int?,
  ) : MonitoringPlotEdit {
    override val monitoringPlotId: MonitoringPlotId?
      get() = null
  }

  /**
   * Represents a monitoring plot that should no longer be associated with any substratum at all.
   * This happens when the plot's area is no longer within the site boundary.
   *
   * This will always be on the list of monitoring plot edits for the plot's existing substratum.
   */
  data class Eject(override val monitoringPlotId: MonitoringPlotId) : MonitoringPlotEdit {
    override val permanentIndex: Int?
      get() = null
  }
}
