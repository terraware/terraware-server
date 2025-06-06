package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.DISCLAIMERS
import com.terraformation.backend.db.default_schema.tables.references.USER_DISCLAIMERS
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class DisclaimerModel(
    val id: DisclaimerId,
    val content: String,
    /** The time when the current user has accepted the disclaimer, if they had */
    val acceptedOn: Instant? = null,
    val effectiveOn: Instant,
    /** List of all users that had accepted the disclaimer and the time of acceptance. */
    val users: Map<UserId, Instant>? = null,
) {
  companion object {
    fun of(record: Record): DisclaimerModel {
      return DisclaimerModel(
          id = record[DISCLAIMERS.ID]!!,
          content = record[DISCLAIMERS.CONTENT]!!,
          acceptedOn = record[USER_DISCLAIMERS.ACCEPTED_ON],
          effectiveOn = record[DISCLAIMERS.EFFECTIVE_ON]!!,
      )
    }

    fun of(record: Record, usersField: Field<Map<UserId, Instant>>): DisclaimerModel {
      return DisclaimerModel(
          id = record[DISCLAIMERS.ID]!!,
          content = record[DISCLAIMERS.CONTENT]!!,
          effectiveOn = record[DISCLAIMERS.EFFECTIVE_ON]!!,
          users = record[usersField],
      )
    }
  }
}
