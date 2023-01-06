package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate

enum class AccessionHistoryType {
  Created,
  /** Manual edits to the accession's seed quantity, not including withdrawals. */
  QuantityUpdated,
  StateChanged,
  /** A withdrawal for the purpose of viability testing. */
  ViabilityTesting,
  /** A withdrawal for a purpose other than viability testing. */
  Withdrawal,
}

data class AccessionHistoryModel(
    /**
     * When the underlying object representing the event was created. Used as a secondary sort key
     * when multiple events happened on the same date.
     */
    val createdTime: Instant,
    /**
     * The effective date of the event. For example, if the event is a withdrawal, this would be the
     * withdrawal date entered by the user (which might not be the same day as [createdTime].)
     *
     * If the event's date was entered by the user, this is the user-entered date. If it is based on
     * a timestamp, this date is in the seed bank's time zone.
     */
    val date: LocalDate,
    val description: String,
    /**
     * The full name of the user who was responsible for the event. This may or may not be a
     * registered Terraware user; in some places in the app, we let people enter names of other
     * people as plain text fields.
     */
    val fullName: String?,
    val type: AccessionHistoryType,
    /** If the event was attributed to a Terraware user, that user's ID. */
    val userId: UserId?,
) : Comparable<AccessionHistoryModel> {
  /**
   * Compares two history models for sorting purposes. We sort history in reverse [date] order, with
   * each day's history sorted in reverse [createdTime] order. We do it this way rather than just
   * sorting by [createdTime] because some kinds of history can be backdated, e.g., on Tuesday you
   * can tell the system that a withdrawal happened on Monday, and it should show up with Monday's
   * date in the history records.
   */
  override fun compareTo(other: AccessionHistoryModel): Int {
    // We sort history in reverse time order, so the comparison results are flipped here.
    return when {
      date < other.date -> 1
      date > other.date -> -1
      createdTime < other.createdTime -> 1
      createdTime > other.createdTime -> -1
      else -> other.type.ordinal - type.ordinal
    }
  }
}
