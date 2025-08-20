package com.terraformation.backend.tracking.model

data class PlantingSiteValidationFailure(
    val type: PlantingSiteValidationFailureType,
    val zoneName: String? = null,
    val subzoneName: String? = null,
    val conflictsWith: Set<String>? = null,
) {
  companion object {
    fun duplicateSubzoneName(subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.DuplicateSubzoneName,
            zoneName,
            subzoneName,
        )

    fun duplicateZoneName(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.DuplicateZoneName, zoneName)

    fun exclusionWithoutBoundary() =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ExclusionWithoutBoundary)

    fun siteTooLarge() =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.SiteTooLarge)

    fun subzoneBoundaryOverlaps(conflictsWith: Set<String>, subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneBoundaryOverlaps,
            zoneName,
            subzoneName,
            conflictsWith,
        )

    fun subzoneInExclusionArea(subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneInExclusionArea,
            zoneName,
            subzoneName,
        )

    fun subzoneNotInZone(subzoneName: String, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubzoneNotInZone,
            zoneName,
            subzoneName,
        )

    fun zoneBoundaryOverlaps(conflictsWith: Set<String>, zoneName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.ZoneBoundaryOverlaps,
            zoneName,
            conflictsWith = conflictsWith,
        )

    fun zoneHasNoSubzones(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ZoneHasNoSubzones, zoneName)

    fun zoneNotInSite(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ZoneNotInSite, zoneName)

    fun zonesWithoutSiteBoundary() =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ZonesWithoutSiteBoundary)

    fun zoneTooSmall(zoneName: String) =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ZoneTooSmall, zoneName)
  }
}
