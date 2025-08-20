package com.terraformation.backend.funder.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import java.time.Instant
import org.jooq.Record

/**
 * A funder user with external facing information only. This is similar to the FunderUser object,
 * but without sensitive information such as Auth ID.
 */
data class FunderUserModel(
    val userId: UserId,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val createdTime: Instant,
    val accountCreated: Boolean,
) {
  companion object {
    fun of(record: Record): FunderUserModel {
      return with(FUNDING_ENTITY_USERS.users) {
        FunderUserModel(
            userId = record[ID]!!,
            email = record[EMAIL]!!,
            firstName = record[FIRST_NAME],
            lastName = record[LAST_NAME],
            createdTime = record[CREATED_TIME]!!,
            accountCreated = record[AUTH_ID.isNotNull],
        )
      }
    }
  }
}
