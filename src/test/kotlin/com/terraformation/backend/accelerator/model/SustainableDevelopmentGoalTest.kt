package com.terraformation.backend.accelerator.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SustainableDevelopmentGoalTest {

  @Test
  fun `sdgNumber works correctly for single and double digits`() {
    assertEquals(4, SustainableDevelopmentGoal.QualityEducation.sdgNumber)
    assertEquals(15, SustainableDevelopmentGoal.LifeOnLand.sdgNumber)
  }

  @Test
  fun `forJsonValue throws exception when not found`() {
    assertThrows<IllegalArgumentException> { SustainableDevelopmentGoal.forJsonValue("1000") }
  }

  @Test
  fun `forJsonValue works correctly for single and double digits`() {
    assertEquals(SustainableDevelopmentGoal.NoPoverty, SustainableDevelopmentGoal.forJsonValue("1"))

    assertEquals(
        SustainableDevelopmentGoal.Partnerships, SustainableDevelopmentGoal.forJsonValue("17"))
  }

  @Test
  fun `forJsonValue works correctly for names`() {
    assertEquals(
        SustainableDevelopmentGoal.NoPoverty, SustainableDevelopmentGoal.forJsonValue("NoPoverty"))
    assertEquals(
        SustainableDevelopmentGoal.ZeroHunger,
        SustainableDevelopmentGoal.forJsonValue("ZeroHunger"))
    assertEquals(
        SustainableDevelopmentGoal.GoodHealth,
        SustainableDevelopmentGoal.forJsonValue("GoodHealth"))
    assertEquals(
        SustainableDevelopmentGoal.QualityEducation,
        SustainableDevelopmentGoal.forJsonValue("QualityEducation"))
    assertEquals(
        SustainableDevelopmentGoal.GenderEquality,
        SustainableDevelopmentGoal.forJsonValue("GenderEquality"))
    assertEquals(
        SustainableDevelopmentGoal.CleanWater,
        SustainableDevelopmentGoal.forJsonValue("CleanWater"))
    assertEquals(
        SustainableDevelopmentGoal.AffordableEnergy,
        SustainableDevelopmentGoal.forJsonValue("AffordableEnergy"))
    assertEquals(
        SustainableDevelopmentGoal.DecentWork,
        SustainableDevelopmentGoal.forJsonValue("DecentWork"))
    assertEquals(
        SustainableDevelopmentGoal.Industry, SustainableDevelopmentGoal.forJsonValue("Industry"))
    assertEquals(
        SustainableDevelopmentGoal.ReducedInequalities,
        SustainableDevelopmentGoal.forJsonValue("ReducedInequalities"))
    assertEquals(
        SustainableDevelopmentGoal.SustainableCities,
        SustainableDevelopmentGoal.forJsonValue("SustainableCities"))
    assertEquals(
        SustainableDevelopmentGoal.ResponsibleConsumption,
        SustainableDevelopmentGoal.forJsonValue("ResponsibleConsumption"))
    assertEquals(
        SustainableDevelopmentGoal.ClimateAction,
        SustainableDevelopmentGoal.forJsonValue("ClimateAction"))
    assertEquals(
        SustainableDevelopmentGoal.LifeBelowWater,
        SustainableDevelopmentGoal.forJsonValue("LifeBelowWater"))
    assertEquals(
        SustainableDevelopmentGoal.LifeOnLand,
        SustainableDevelopmentGoal.forJsonValue("LifeOnLand"))
    assertEquals(SustainableDevelopmentGoal.Peace, SustainableDevelopmentGoal.forJsonValue("Peace"))
    assertEquals(
        SustainableDevelopmentGoal.Partnerships,
        SustainableDevelopmentGoal.forJsonValue("Partnerships"))
  }

  @Test
  fun `forJsonValue throws when doesn't exist`() {
    assertThrows<IllegalArgumentException> { SustainableDevelopmentGoal.forJsonValue("BadValue") }
  }
}
