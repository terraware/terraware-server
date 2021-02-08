package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.WithdrawalPurpose
import com.terraformation.seedbank.services.equalsIgnoreScale
import java.math.BigDecimal
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

interface ConcreteWithdrawal : WithdrawalFields {
  override val id: Long
  override val seedsWithdrawn: Int
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
) : ConcreteWithdrawal
