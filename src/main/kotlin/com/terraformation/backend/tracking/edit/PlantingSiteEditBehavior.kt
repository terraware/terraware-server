package com.terraformation.backend.tracking.edit

/** Which version of site editing behavior to use for a given planting site edit. */
enum class PlantingSiteEditBehavior {
  /**
   * Original behavior with restrictions on which boundaries can be edited once a site has
   * observations.
   */
  Restricted,
  /**
   * New behavior that allows arbitrary map edits including changing boundaries between planting
   * zones, even on sites with observations.
   */
  Flexible
}
