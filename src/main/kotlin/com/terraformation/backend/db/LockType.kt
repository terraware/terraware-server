package com.terraformation.backend.db

/**
 * Types of locks that can be acquired by the [LockService].
 *
 * In the current implementation of [LockService], each lock must have a unique numeric identifier.
 */
enum class LockType(val key: Long) {
  GBIF_IMPORT(10000),
  OBSERVATION_RESULTS_RECALCULATION(10001),
  OBSERVATION_RESULTS_FORCED_RECALCULATION(10002),
}
