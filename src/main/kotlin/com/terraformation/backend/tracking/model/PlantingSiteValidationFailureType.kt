package com.terraformation.backend.tracking.model

enum class PlantingSiteValidationFailureType {
  CannotRemovePlantedSubzone,
  CannotSplitSubzone,
  CannotSplitZone,
  DuplicateSubzoneName,
  DuplicateZoneName,
  ExclusionWithoutBoundary,
  SiteTooLarge,
  SubzoneBoundaryChanged,
  SubzoneBoundaryOverlaps,
  SubzoneInExclusionArea,
  SubzoneNotInZone,
  ZoneBoundaryChanged,
  ZoneBoundaryOverlaps,
  ZoneHasNoSubzones,
  ZoneNotInSite,
  ZoneTooSmall,
  ZonesWithoutSiteBoundary,
}
