package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.tables.references.DISCLAIMERS
import com.terraformation.backend.db.default_schema.tables.references.USER_DISCLAIMERS
import java.time.Instant
import org.jooq.Record

data class UserDisclaimerModel(
    val id: DisclaimerId,
    val content: String,
    /** The time when the current user has accepted the disclaimer, if they had */
    val acceptedOn: Instant? = null,
    val effectiveOn: Instant,
) {
  companion object {
    fun of(record: Record): UserDisclaimerModel {
      return UserDisclaimerModel(
          id = record[DISCLAIMERS.ID]!!,
          content = record[DISCLAIMERS.CONTENT]!!,
          acceptedOn = record[USER_DISCLAIMERS.ACCEPTED_ON],
          effectiveOn = record[DISCLAIMERS.EFFECTIVE_ON]!!,
      )
    }
  }
}
