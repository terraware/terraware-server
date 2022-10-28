package com.terraformation.backend.seedbank.model

import java.math.BigDecimal

data class AccessionSummaryStatistics(
    val accessions: Int,
    val species: Int,
    val subtotalBySeedCount: Long,
    val subtotalByWeightEstimate: Long,
    val totalSeedsRemaining: Long,
    val unknownQuantityAccessions: Int,
) {
  constructor(
      accessions: Int,
      species: Int,
      subtotalBySeedCount: BigDecimal,
      subtotalByWeightEstimate: BigDecimal,
      unknownQuantityAccessions: BigDecimal,
  ) : this(
      accessions = accessions,
      species = species,
      subtotalBySeedCount = subtotalBySeedCount.toLong(),
      subtotalByWeightEstimate = subtotalByWeightEstimate.toLong(),
      totalSeedsRemaining = subtotalBySeedCount.toLong() + subtotalByWeightEstimate.toLong(),
      unknownQuantityAccessions = unknownQuantityAccessions.toInt(),
  )

  /** Returns true if any of the fields have nonzero values. */
  fun isNonZero(): Boolean {
    return accessions != 0 ||
        species != 0 ||
        subtotalBySeedCount != 0L ||
        subtotalByWeightEstimate != 0L ||
        totalSeedsRemaining != 0L ||
        unknownQuantityAccessions != 0
  }
}
