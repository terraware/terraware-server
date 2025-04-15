package com.terraformation.backend.accelerator.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

val startingDigitRegex = Regex("^\\d+")

enum class SustainableDevelopmentGoal(val displayName: String) {
  NoPoverty("1. No Poverty"),
  ZeroHunger("2. Zero Hunger"),
  GoodHealth("3. Good Health and Well-Being"),
  QualityEducation("4. Quality Education"),
  GenderEquality("5. Gender Equality"),
  CleanWater("6. Clean Water and Sanitation"),
  AffordableEnergy("7. Affordable and Clean Energy"),
  DecentWork("8. Decent Work and Economic Growth"),
  Industry("9. Industry, Innovation, and Infrastructure"),
  ReducedInequalities("10. Reduced Inequalities"),
  SustainableCities("11. Sustainable Cities and Communities"),
  ResponsibleConsumption("12. Responsible Consumption and Production"),
  ClimateAction("13. Climate Action"),
  LifeBelowWater("14. Life Below Water"),
  LifeOnLand("15. Life on Land"),
  Peace("16. Peace, Justice, and Strong Institutions"),
  Partnerships("17. Partnerships for the Goals");

  @get:JsonValue val sdgNumber: String = startingDigitRegex.find(displayName)?.value ?: ""

  companion object {
    private val bySdgNumber: Map<String, SustainableDevelopmentGoal> by lazy {
      SustainableDevelopmentGoal.entries.associateBy { it.sdgNumber }
    }

    @JsonCreator
    @JvmStatic
    fun forJsonValue(value: String): SustainableDevelopmentGoal {
      val sdgNumber = startingDigitRegex.find(value)?.value ?: ""
      return SustainableDevelopmentGoal.bySdgNumber[sdgNumber]
          ?: throw IllegalArgumentException("Unknown goal $value")
    }
  }
}
