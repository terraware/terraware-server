package com.terraformation.backend.seedbank.model

import java.math.BigDecimal

data class AccessionSummaryStatistics(
    val accessions: Int,
    val species: Int,
    val subtotalBySeedCount: Long,
    val subtotalByWeightEstimate: Long,
    val totalSeedsRemaining: Long,
    val seedsWithdrawn: Long,
    val unknownQuantityAccessions: Int,
) {
  constructor(
      accessions: Int,
      species: Int,
      subtotalBySeedCount: BigDecimal,
      subtotalByWeightEstimate: BigDecimal,
      seedsWithdrawn: BigDecimal,
      unknownQuantityAccessions: BigDecimal,
  ) : this(
      accessions = accessions,
      species = species,
      subtotalBySeedCount = subtotalBySeedCount.toLong(),
      subtotalByWeightEstimate = subtotalByWeightEstimate.toLong(),
      totalSeedsRemaining = subtotalBySeedCount.toLong() + subtotalByWeightEstimate.toLong(),
      seedsWithdrawn = seedsWithdrawn.toLong(),
      unknownQuantityAccessions = unknownQuantityAccessions.toInt(),
  )

  val totalSeedsStored: Long
    get() = totalSeedsRemaining + seedsWithdrawn

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
