package com.terraformation.backend.tracking.model

enum class PlantingSiteValidationFailureType {
  CannotRemovePlantedSubzone,
  CannotSplitSubzone,
  CannotSplitZone,
  SubzoneBoundaryChanged,
  ZoneBoundaryChanged,
}
