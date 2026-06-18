package com.terraformation.backend.species.event

/**
 * Published when the WCVP taxon dataset is imported. This can trigger recalculation of derived data
 * such as the distribution/botanical country mapping.
 */
class WcvpImportedEvent {
  override fun equals(other: Any?) = other is WcvpImportedEvent

  override fun hashCode() = javaClass.hashCode()
}
