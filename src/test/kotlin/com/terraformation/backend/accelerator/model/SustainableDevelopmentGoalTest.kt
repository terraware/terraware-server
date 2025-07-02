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
        SustainableDevelopmentGoal.ResponsibleConsumption,
        SustainableDevelopmentGoal.forJsonValue("ResponsibleConsumption"))
  }

  @Test
  fun `forJsonValue throws when doesn't exist`() {
    assertThrows<IllegalArgumentException> { SustainableDevelopmentGoal.forJsonValue("BadValue") }
  }
}
