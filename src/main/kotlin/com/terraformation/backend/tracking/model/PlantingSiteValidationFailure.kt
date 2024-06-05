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

    fun duplicateSubzoneName(subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.DuplicateSubzoneName, zoneName, subzoneName)

    fun duplicateZoneName(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.DuplicateZoneName, zoneName)

    fun siteTooLarge() =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.SiteTooLarge)

    fun subzoneBoundaryChanged(conflictsWith: Set<String>, subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneBoundaryChanged,
            zoneName,
            subzoneName,
            conflictsWith)

    fun subzoneBoundaryOverlaps(conflictsWith: Set<String>, subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneBoundaryOverlaps,
            zoneName,
            subzoneName,
            conflictsWith)

    fun subzoneInExclusionArea(subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneInExclusionArea, zoneName, subzoneName)

    fun subzoneNotInZone(subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneNotInZone, zoneName, subzoneName)

    fun zoneBoundaryChanged(conflictsWith: Set<String>, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.ZoneBoundaryChanged,
            zoneName,
            conflictsWith = conflictsWith)

    fun zoneBoundaryOverlaps(conflictsWith: Set<String>, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.ZoneBoundaryOverlaps,
            zoneName,
            conflictsWith = conflictsWith)

    fun zoneHasNoSubzones(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ZoneHasNoSubzones, zoneName)

    fun zoneNotInSite(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ZoneNotInSite, zoneName)

    fun zoneTooSmall(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ZoneTooSmall, zoneName)
  }
}
