package com.terraformation.backend.gis.event

/**
 * Published when the botanical countries dataset is imported. This can trigger recalculation of
 * derived data such as the mapping to WCVP distributions.
 */
class BotanicalCountriesImportedEvent {
  override fun equals(other: Any?) = other is BotanicalCountriesImportedEvent

  override fun hashCode() = javaClass.hashCode()
}
