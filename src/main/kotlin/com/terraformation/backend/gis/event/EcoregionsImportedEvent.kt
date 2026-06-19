package com.terraformation.backend.gis.event

/**
 * Published when the ecoregions dataset is imported. This can trigger recalculation of derived data
 * such as the ecoregion/botanical country mapping.
 */
class EcoregionsImportedEvent {
  override fun equals(other: Any?) = other is EcoregionsImportedEvent

  override fun hashCode() = javaClass.hashCode()
}
