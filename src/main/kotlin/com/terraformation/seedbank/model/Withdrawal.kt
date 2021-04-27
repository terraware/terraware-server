package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.WithdrawalPurpose
import com.terraformation.seedbank.services.compareNullsFirst
import java.time.LocalDate

interface WithdrawalFields {
  val id: Long?
    get() = null
  val date: LocalDate
  val purpose: WithdrawalPurpose
  val destination: String?
    get() = null
  val notes: String?
    get() = null
  val staffResponsible: String?
    get() = null
  val germinationTest: GerminationTestFields?
    get() = null
  val germinationTestId: Long?
    get() = null
  val remaining: SeedQuantityModel?
  /** The user-entered withdrawal quantity. */
  val withdrawn: SeedQuantityModel?
  /**
   * The server-calculated withdrawal weight based on the difference between [remaining] on this
   * withdrawal and the previous one. Only valid for weight-based accessions.
   */
  val weightDifference: SeedQuantityModel?

  fun validate() {
    withdrawn?.quantity?.signum()?.let { signum ->
      if (signum <= 0) {
        throw IllegalArgumentException("Withdrawn quantity must be greater than 0")
      }
    }
  }

  /**
   * Compares two withdrawals for equality disregarding scale differences in quantities and also
   * ignoring differences in modification timestamp.
   */
  fun fieldsEqual(other: WithdrawalFields): Boolean {
    return id == other.id &&
        date == other.date &&
        purpose == other.purpose &&
        destination == other.destination &&
        germinationTestId == other.germinationTestId &&
        notes == other.notes &&
        staffResponsible == other.staffResponsible &&
        remaining.equalsIgnoreScale(other.remaining) &&
        weightDifference.equalsIgnoreScale(other.weightDifference) &&
        withdrawn.equalsIgnoreScale(other.withdrawn)
  }

  fun compareByTime(other: WithdrawalFields): Int {
    val dateComparison = date.compareTo(other.date)
    return if (dateComparison != 0) {
      dateComparison
    } else {
      val idComparison = id.compareNullsFirst(other.id)
      if (idComparison != 0) {
        idComparison
      } else {
        // No useful sort order, but we want a stable one
        hashCode().compareTo(other.hashCode())
      }
    }
  }

  fun calculateEstimatedQuantity(): SeedQuantityModel? = withdrawn ?: weightDifference

  fun withDate(value: LocalDate): WithdrawalFields
  fun withGerminationTest(value: GerminationTestFields): WithdrawalFields
  fun withRemaining(value: SeedQuantityModel): WithdrawalFields
  fun withWeightDifference(value: SeedQuantityModel): WithdrawalFields
}

data class GerminationTestWithdrawal(
    override val id: Long? = null,
    val accessionId: Long? = null,
    override val date: LocalDate,
    override val destination: String? = null,
    override val notes: String? = null,
    override val staffResponsible: String? = null,
    override val germinationTestId: Long? = null,
    override val remaining: SeedQuantityModel?,
    override val withdrawn: SeedQuantityModel?,
    override val germinationTest: GerminationTestFields,
    override val weightDifference: SeedQuantityModel? = null,
) : WithdrawalFields {
  override val purpose
    get() = WithdrawalPurpose.GerminationTesting

  override fun withDate(value: LocalDate) = copy(date = value)
  override fun withGerminationTest(value: GerminationTestFields) =
      copy(germinationTest = value, germinationTestId = value.id)
  override fun withRemaining(value: SeedQuantityModel) =
      copy(remaining = value, germinationTest = germinationTest.withRemaining(value))
  override fun withWeightDifference(value: SeedQuantityModel) = copy(weightDifference = value)
}

data class WithdrawalModel(
    override val id: Long,
    val accessionId: Long,
    override val date: LocalDate,
    override val purpose: WithdrawalPurpose,
    override val destination: String? = null,
    override val notes: String? = null,
    override val staffResponsible: String? = null,
    override val germinationTestId: Long? = null,
    override val remaining: SeedQuantityModel,
    override val withdrawn: SeedQuantityModel? = null,
    override val germinationTest: GerminationTestFields? = null,
    override val weightDifference: SeedQuantityModel? = null,
) : WithdrawalFields {
  override fun withDate(value: LocalDate) = copy(date = value)
  override fun withGerminationTest(value: GerminationTestFields) =
      copy(germinationTest = value, germinationTestId = value.id)
  override fun withRemaining(value: SeedQuantityModel) =
      copy(remaining = value, germinationTest = germinationTest?.withRemaining(value))
  override fun withWeightDifference(value: SeedQuantityModel) = copy(weightDifference = value)
}
