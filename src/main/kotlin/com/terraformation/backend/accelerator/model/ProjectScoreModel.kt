package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_SCORES
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
    fun totalScore(scores: Collection<ProjectScoreModel<*>>): BigDecimal? {
      val validScoreValues = scores.mapNotNull { it.score }
      return if (validScoreValues.isNotEmpty()) {
        validScoreValues.average().toBigDecimal().setScale(2, RoundingMode.HALF_UP)
      } else {
        null
      }
    }
  }
}

typealias ExistingProjectScoreModel = ProjectScoreModel<Instant>

typealias NewProjectScoreModel = ProjectScoreModel<Nothing?>
