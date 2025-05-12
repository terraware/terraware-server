package com.terraformation.backend.documentproducer.event

enum class VariableUpdatedSource {
  VariablesUploaded,
  ModulesUploaded,
  DeliverablesUploaded,
}

open class VariablesUpdatedEvent(val source: VariableUpdatedSource) {
  val message = "Variables updated from source: $source"
}
