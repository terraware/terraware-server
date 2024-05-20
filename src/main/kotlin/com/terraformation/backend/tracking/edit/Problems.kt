package com.terraformation.backend.tracking.edit

interface PlantingSiteEditProblem {
  val conflictsWith: Set<String>?
    get() = null

  val subzoneName: String?
    get() = null

  val zoneName: String?

  data class CannotRemovePlantedSubzone(
      override val subzoneName: String,
      override val zoneName: String,
  ) : PlantingSiteEditProblem

  data class CannotSplitSubzone(
      override val conflictsWith: Set<String>,
      override val subzoneName: String,
      override val zoneName: String,
  ) : PlantingSiteEditProblem

  data class CannotSplitZone(
      override val conflictsWith: Set<String>,
      override val zoneName: String,
  ) : PlantingSiteEditProblem

  data class SubzoneBoundaryChanged(
      override val conflictsWith: Set<String>,
      override val subzoneName: String,
      override val zoneName: String,
  ) : PlantingSiteEditProblem

  data class ZoneBoundaryChanged(
      override val conflictsWith: Set<String>,
      override val zoneName: String,
  ) : PlantingSiteEditProblem
}
