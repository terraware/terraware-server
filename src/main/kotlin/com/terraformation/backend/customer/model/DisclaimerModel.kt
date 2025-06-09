package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.DISCLAIMERS
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class DisclaimerModel(
    val id: DisclaimerId,
    val content: String,
    val effectiveOn: Instant,
    /** List of all users that had accepted the disclaimer and the time of acceptance. */
    val users: Map<UserId, Instant>,
) {
  companion object {
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
