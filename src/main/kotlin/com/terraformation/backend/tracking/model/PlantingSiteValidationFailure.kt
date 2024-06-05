package com.terraformation.backend.tracking.model

data class PlantingSiteValidationFailure(
    val type: PlantingSiteValidationFailureType,
    val zoneName: String? = null,
    val subzoneName: String? = null,
    val conflictsWith: Set<String>? = null,
) {
  companion object {
    fun cannotRemovePlantedSubzone(subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.CannotRemovePlantedSubzone, zoneName, subzoneName)

    fun cannotSplitSubzone(conflictsWith: Set<String>, subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.CannotSplitSubzone,
            zoneName,
            subzoneName,
            conflictsWith)

    fun cannotSplitZone(conflictsWith: Set<String>, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.CannotSplitZone,
            zoneName,
            conflictsWith = conflictsWith)

    fun subzoneBoundaryChanged(conflictsWith: Set<String>, subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneBoundaryChanged,
            zoneName,
            subzoneName,
            conflictsWith)

    fun zoneBoundaryChanged(conflictsWith: Set<String>, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.ZoneBoundaryChanged,
            zoneName,
            conflictsWith = conflictsWith)
  }
}
