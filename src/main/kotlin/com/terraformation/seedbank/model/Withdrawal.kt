package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.WithdrawalPurpose
import com.terraformation.seedbank.services.equalsIgnoreScale
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

interface WithdrawalFields {
  val id: Long?
    get() = null
  val date: LocalDate
  val purpose: WithdrawalPurpose
  val seedsWithdrawn: Int?
    get() = null
  val gramsWithdrawn: BigDecimal?
    get() = null
  val destination: String?
    get() = null
  val notes: String?
    get() = null
  val staffResponsible: String?
    get() = null
  val germinationTestId: Long?
    get() = null

  /**
   * Computes a withdrawal's seed count. A withdrawal can be sized in number of seeds or number of
   * grams. Weight-based sizing is only available if the accession has seed weight information,
   * specifically a subset count and subset weight.
   */
  fun computeSeedsWithdrawn(accession: AccessionFields, isExistingWithdrawal: Boolean): Int {
    val desiredGrams = gramsWithdrawn
    val desiredSeeds = seedsWithdrawn

    if (desiredGrams == null && desiredSeeds == null) {
      throw IllegalArgumentException("Withdrawal must have either a seed count or a weight.")
    }

    if (desiredGrams != null && desiredSeeds != null && !isExistingWithdrawal) {
      throw IllegalArgumentException("New withdrawals can have a seed count or a weight, not both.")
    }

    return if (desiredGrams != null) {
      val subsetWeightGrams = accession.subsetWeightGrams
      val subsetCount = accession.subsetCount

      if (desiredGrams <= BigDecimal.ZERO) {
        throw IllegalArgumentException("Withdrawal weight must be greater than zero.")
      }

      if (subsetWeightGrams != null && subsetCount != null) {
        BigDecimal(subsetCount)
            .multiply(desiredGrams)
            .divide(subsetWeightGrams, 5, RoundingMode.CEILING)
            .setScale(0, RoundingMode.CEILING)
            .toInt()
      } else if (desiredSeeds != null && isExistingWithdrawal) {
        // If the user removed the weight from an existing accession after there were already
        // weight-based withdrawals, retain the previously-computed withdrawal count.
        desiredSeeds
      } else {
        throw IllegalArgumentException(
            "Withdrawal can only be measured by weight if accession was measured by weight.")
      }
    } else {
      desiredSeeds!!
    }
  }

  /**
   * Compares two withdrawals for equality disregarding scale differences in gramsWithdrawn and also
   * ignoring differences in modification timestamp.
   */
  fun fieldsEqual(other: WithdrawalFields): Boolean {
    return id == other.id &&
        date == other.date &&
        purpose == other.purpose &&
        seedsWithdrawn == other.seedsWithdrawn &&
        gramsWithdrawn.equalsIgnoreScale(other.gramsWithdrawn) &&
        destination == other.destination &&
        notes == other.notes &&
        staffResponsible == other.staffResponsible
  }
}

data class GerminationTestWithdrawal(
    override val id: Long?,
    val accessionId: Long,
    override val date: LocalDate,
    override val seedsWithdrawn: Int,
    override val gramsWithdrawn: BigDecimal? = null,
    override val destination: String? = null,
    override val notes: String? = null,
    override val staffResponsible: String? = null,
    override val germinationTestId: Long? = null,
) : WithdrawalFields {
  override val purpose
    get() = WithdrawalPurpose.GerminationTesting
}

data class WithdrawalModel(
    override val id: Long,
    val accessionId: Long,
    override val date: LocalDate,
    override val purpose: WithdrawalPurpose,
    override val seedsWithdrawn: Int,
    override val gramsWithdrawn: BigDecimal? = null,
    override val destination: String? = null,
    override val notes: String? = null,
    override val staffResponsible: String? = null,
    override val germinationTestId: Long? = null,
) : WithdrawalFields
