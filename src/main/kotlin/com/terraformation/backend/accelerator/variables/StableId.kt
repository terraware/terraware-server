package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.db.default_schema.LandUseModelType

enum class StableId(val value: String) {
  COUNTRY("1"),
  APPLICATION_RESTORABLE_LAND("2"),
  PROJECT_TYPE("3"),
  LAND_USE_MODEL_TYPES("4"),
  NATIVE_FOREST_LAND_USE_HECTARE("5"),
  MONOCULTURE_LAND_USE_HECTARE("7"),
  SUSTAINABLE_TIMBER_LAND_USE_HECTARE("9"),
  OTHER_TIMBER_LAND_USE_HECTARE("11"),
  MANGROVES_LAND_USE_HECTARE("13"),
  AGROFORESTRY_LAND_USE_HECTARE("15"),
  SILVOPASTURE_LAND_USE_HECTARE("17"),
  OTHER_LAND_USE_HECTARE("19"),
  NUM_SPECIES("22"),
  TOTAL_EXPANSION_POTENTIAL("24"),
  CONTACT_NAME("25"),
  CONTACT_EMAIL("26"),
  WEBSITE("27"),
  TF_RESTORABLE_LAND("429"),
  PER_HECTARE_ESTIMATED_BUDGET("430"),
  MIN_CARBON_ACCUMULATION("431"),
  MAX_CARBON_ACCUMULATION("432"),
  CARBON_CAPACITY("433"),
  ANNUAL_CARBON("434"),
  TOTAL_CARBON("435"),
  DEAL_DESCRIPTION("436"),
  INVESTMENT_THESIS("437"),
  FAILURE_RISK("438"),
  WHAT_NEEDS_TO_BE_TRUE("439");

  companion object {
    val landUseHectaresByLandUseModel =
        mapOf(
            LandUseModelType.Agroforestry to AGROFORESTRY_LAND_USE_HECTARE,
            LandUseModelType.Mangroves to MANGROVES_LAND_USE_HECTARE,
            LandUseModelType.Monoculture to MONOCULTURE_LAND_USE_HECTARE,
            LandUseModelType.NativeForest to NATIVE_FOREST_LAND_USE_HECTARE,
            LandUseModelType.OtherLandUseModel to OTHER_LAND_USE_HECTARE,
            LandUseModelType.OtherTimber to OTHER_TIMBER_LAND_USE_HECTARE,
            LandUseModelType.Silvopasture to SILVOPASTURE_LAND_USE_HECTARE,
            LandUseModelType.SustainableTimber to SUSTAINABLE_TIMBER_LAND_USE_HECTARE,
        )

    fun from(value: String): StableId? = entries.find { it.value == value }
  }
}
