package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_SCORES
import com.terraformation.backend.log.perClassLogger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import org.jooq.Record

data class ProjectScoreModel<INSTANT : Instant?>(
    val category: ScoreCategory,
    val modifiedTime: INSTANT,
    val qualitative: String?,
    val score: Int?,
) {
  companion object {
    private val log = perClassLogger()

    fun of(record: Record) =
        ExistingProjectScoreModel(
            category = record[PROJECT_SCORES.SCORE_CATEGORY_ID]!!,
            modifiedTime = record[PROJECT_SCORES.MODIFIED_TIME]!!,
            qualitative = record[PROJECT_SCORES.QUALITATIVE],
            score = record[PROJECT_SCORES.SCORE],
        )

    /**
     * Returns the total (average) score for a set of per-category scores, rounded to 2 decimal
     * places.
     */
    fun totalScore(phase: CohortPhase, scores: Collection<ProjectScoreModel<*>>): BigDecimal? {
      return when (phase) {
        CohortPhase.Phase0DueDiligence -> phase0Total(scores)
        CohortPhase.Phase1FeasibilityStudy -> phase1Total(scores)
        else -> {
          log.error("BUG! Should not be trying to calculate score for phase $phase")
          null
        }
      }
    }

    private val projectLeadCategories =
        setOf(
            ScoreCategory.ExpansionPotential,
            ScoreCategory.ExperienceAndUnderstanding,
            ScoreCategory.ValuesAlignment,
            ScoreCategory.ResponsivenessAndAttentionToDetail,
        )

    /**
     * The total score of phase 0 is a weighted average based on category. The project lead scores
     * are given a combined weight of 1; since phase 0 requires that all scores be present, we can
     * get the same effect by giving each of the four of them a weight of 0.25.
     */
    private val phase0Weights =
        mapOf(
            ScoreCategory.ExpansionPotential to 0.25,
            ScoreCategory.ExperienceAndUnderstanding to 0.25,
            ScoreCategory.Finance to 2.0,
            ScoreCategory.Forestry to 1.0,
            ScoreCategory.GIS to 1.0,
            ScoreCategory.Legal to 1.0,
            ScoreCategory.OperationalCapacity to 1.0,
            ScoreCategory.ResponsivenessAndAttentionToDetail to 0.25,
            ScoreCategory.SocialImpact to 1.0,
            ScoreCategory.ValuesAlignment to 0.25,
        )

    /**
     * Total of the weights of the phase 0 categories. The sum of the weighted scores is divided by
     * this to get the weighted average.
     */
    private val phase0TotalWeight = phase0Weights.values.sum()

    /**
     * Returns the "total" score (really a weighted average) for phase 0. The phase 0 score is only
     * calculated if there are score values for _all_ of the categories that are required for phase
     * 0, which excludes a couple of categories that are only in phase 1.
     */
    private fun phase0Total(scores: Collection<ProjectScoreModel<*>>): BigDecimal? {
      val weightedScores =
          scores.mapNotNull { scoreModel ->
            phase0Weights[scoreModel.category]?.let { weight -> scoreModel.score?.times(weight) }
          }

      // For phase 0, the total is only calculated when all categories have values.
      if (weightedScores.size != phase0Weights.size) {
        return null
      }

      return (weightedScores.sum() / phase0TotalWeight)
          .toBigDecimal()
          .setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * Returns the "total" score (really the average) for phase 1. The phase 1 score is calculated
     * if there are score values for _any_ of the categories.
     */
    private fun phase1Total(scores: Collection<ProjectScoreModel<*>>): BigDecimal? {
      // The project lead category scores have to be averaged and treated as a single score when
      // calculating the overall average.
      val projectLeadScores =
          scores.filter { it.category in projectLeadCategories }.mapNotNull { it.score }

      val nonLeadScores =
          scores
              .filterNot { it.category in projectLeadCategories }
              .mapNotNull { it.score?.toDouble() }

      val relevantScores =
          if (projectLeadScores.isNotEmpty()) {
            nonLeadScores + projectLeadScores.average()
          } else {
            nonLeadScores
          }

      return if (relevantScores.isNotEmpty()) {
        relevantScores.average().toBigDecimal().setScale(2, RoundingMode.HALF_UP)
      } else {
        null
      }
    }
  }
}

typealias ExistingProjectScoreModel = ProjectScoreModel<Instant>

typealias NewProjectScoreModel = ProjectScoreModel<Nothing?>
