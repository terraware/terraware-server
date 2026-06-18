package com.terraformation.backend.gis.event

/**
 * Published when the botanical countries dataset is imported. This can trigger recalculation of
 * derived data such as the ecoregion/botanical country mapping.
 */
class BotanicalCountriesImportedEvent {
  override fun equals(other: Any?) = other is BotanicalCountriesImportedEvent

  override fun hashCode() = javaClass.hashCode()
}
