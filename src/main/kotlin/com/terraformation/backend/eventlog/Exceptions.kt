package com.terraformation.backend.eventlog

class CircularEventUpgradePathDetectedException(
    val upgradePath: List<Class<out PersistentEvent>>,
) : IllegalStateException(renderMessage(upgradePath)) {
  companion object {
    private fun renderMessage(upgradePath: List<Class<out PersistentEvent>>): String {
      val prettyPath = upgradePath.joinToString(" -> ") { it.name }
      return "Circular event upgrade path: $prettyPath"
    }
  }
}
