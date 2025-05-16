package com.terraformation.backend.accelerator.model

@Suppress("unused")
enum class CarbonCertification(val displayName: String) {
  CcbVerraStandard("CCB Standard");

  companion object {
    private val byDisplayName = entries.associateBy { it.displayName }

    fun forDisplayName(name: String): CarbonCertification? {
      return byDisplayName[name] ?: throw IllegalArgumentException("Unknown certification: $name")
    }
  }
}
