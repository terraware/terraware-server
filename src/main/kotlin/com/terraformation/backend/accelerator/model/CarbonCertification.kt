package com.terraformation.backend.accelerator.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

@Suppress("unused")
enum class CarbonCertification(@get:JsonValue val displayName: String) {
  CcbVerraStandard("CCB Standard");

  companion object {
    private val byDisplayName = entries.associateBy { it.displayName }

    @JsonCreator
    @JvmStatic
    fun forDisplayName(name: String): CarbonCertification {
      return byDisplayName[name] ?: throw IllegalArgumentException("Unknown certification: $name")
    }
  }
}
