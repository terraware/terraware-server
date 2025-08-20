package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ScoreCategory
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProjectScoreModelTest {
  @Nested
  inner class Phase0 {
    @Test
    fun `treats project lead scores as a single score in the average`() {
      val scores =
          listOf(
              newModel(ScoreCategory.Finance, 1),
              newModel(ScoreCategory.Forestry, 1),
              newModel(ScoreCategory.GIS, 1),
              newModel(ScoreCategory.Legal, 1),
              newModel(ScoreCategory.OperationalCapacity, 1),
              newModel(ScoreCategory.SocialImpact, 1),
              // Project lead categories have scores of 2
              newModel(ScoreCategory.ExpansionPotential, 2),
              newModel(ScoreCategory.ExperienceAndUnderstanding, 2),
              newModel(ScoreCategory.ResponsivenessAndAttentionToDetail, 2),
              newModel(ScoreCategory.ValuesAlignment, 2),
          )

      // Average of 7 values of 1.0 (finance counted twice) and 1 value of 2.0
      assertEquals(
          BigDecimal(9.0 / 8.0).setScale(2, RoundingMode.HALF_UP),
          ProjectScoreModel.totalScore(CohortPhase.Phase0DueDiligence, scores),
      )
    }

    @Test
    fun `gives double weight to finance score`() {
      val scores =
          listOf(
              newModel(ScoreCategory.Finance, 2),
              newModel(ScoreCategory.Forestry, 1),
              newModel(ScoreCategory.GIS, 1),
              newModel(ScoreCategory.Legal, 1),
              newModel(ScoreCategory.OperationalCapacity, 1),
              newModel(ScoreCategory.ExpansionPotential, 1),
              newModel(ScoreCategory.ExperienceAndUnderstanding, 1),
              newModel(ScoreCategory.ResponsivenessAndAttentionToDetail, 1),
              newModel(ScoreCategory.SocialImpact, 1),
              newModel(ScoreCategory.ValuesAlignment, 1),
          )

      // Average of 6 values of 1.0 (including the project lead average) and 2 values of 2.0
      // (finance counted twice)
      assertEquals(
          BigDecimal(10.0 / 8.0).setScale(2, RoundingMode.HALF_UP),
          ProjectScoreModel.totalScore(CohortPhase.Phase0DueDiligence, scores),
      )
    }

    @Test
    fun `returns null if required score is missing`() {
      val scores =
          listOf(
              newModel(ScoreCategory.ExpansionPotential, 1),
              newModel(ScoreCategory.ExperienceAndUnderstanding, 1),
              newModel(ScoreCategory.Finance, 1),
              newModel(ScoreCategory.Forestry, 1),
              newModel(ScoreCategory.GIS, 1),
              newModel(ScoreCategory.Legal, 1),
              newModel(ScoreCategory.OperationalCapacity, 1),
              newModel(ScoreCategory.ResponsivenessAndAttentionToDetail, 1),
              newModel(ScoreCategory.SocialImpact, 1),
              // Missing ValuesAlignment
          )

      assertNull(ProjectScoreModel.totalScore(CohortPhase.Phase0DueDiligence, scores))
    }
  }

  @Nested
  inner class Phase1 {
    @Test
    fun `treats project lead scores as a single score in the average`() {
      val scores =
          listOf(
              newModel(ScoreCategory.SocialImpact, 1),
              // Project lead categories have scores of 2
              newModel(ScoreCategory.ExpansionPotential, 2),
              newModel(ScoreCategory.ValuesAlignment, 3),
          )

      // Average of 1 value of 1.0 and 1 value of 2.5 (average of the two project lead scores)
      assertEquals(
          BigDecimal(3.5 / 2.0).setScale(2, RoundingMode.HALF_UP),
          ProjectScoreModel.totalScore(CohortPhase.Phase1FeasibilityStudy, scores),
      )
    }

    @Test
    fun `calculates average of whichever scores have values`() {
      val scores =
          listOf(
              newModel(ScoreCategory.ExpansionPotential, 2),
              newModel(ScoreCategory.GIS, null),
              newModel(ScoreCategory.SocialImpact, 1),
          )

      assertEquals(
          BigDecimal(3.0 / 2.0).setScale(2, RoundingMode.HALF_UP),
          ProjectScoreModel.totalScore(CohortPhase.Phase1FeasibilityStudy, scores),
      )
    }

    @Test
    fun `returns null if no scores are available`() {
      val scores =
          listOf(
              newModel(ScoreCategory.ExpansionPotential, null),
              newModel(ScoreCategory.SocialImpact, null),
          )

      assertNull(ProjectScoreModel.totalScore(CohortPhase.Phase1FeasibilityStudy, scores))
    }
  }

  @Test
  fun `returns null for non-scored cohort phases`() {
    val scores = listOf(NewProjectScoreModel(ScoreCategory.GIS, null, null, 1))

    assertNull(ProjectScoreModel.totalScore(CohortPhase.Phase2PlanAndScale, scores), "Phase 2")
    assertNull(
        ProjectScoreModel.totalScore(CohortPhase.Phase3ImplementAndMonitor, scores),
        "Phase 3",
    )
  }

  private fun newModel(category: ScoreCategory, score: Int?) =
      NewProjectScoreModel(category, null, null, score)
}
