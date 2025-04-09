package com.terraformation.backend.tracking.model

enum class PlantingSiteValidationFailureType {
  DuplicateSubzoneName,
  DuplicateZoneName,
  ExclusionWithoutBoundary,
  SiteTooLarge,
  SubzoneBoundaryOverlaps,
  SubzoneInExclusionArea,
  SubzoneNotInZone,
  ZoneBoundaryOverlaps,
  ZoneHasNoSubzones,
  ZoneNotInSite,
  ZoneTooSmall,
  ZonesWithoutSiteBoundary,
}
