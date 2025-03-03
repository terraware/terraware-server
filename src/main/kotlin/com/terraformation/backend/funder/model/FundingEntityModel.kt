package com.terraformation.backend.funder.model

import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import java.time.Instant
import org.jooq.Record

class FundingEntityModel(
    val id: FundingEntityId,
    val name: String,
    val createdTime: Instant,
    val modifiedTime: Instant,
) {
  companion object {
    fun of(
        record: Record,
    ): FundingEntityModel {
      return with(FUNDING_ENTITIES) {
        FundingEntityModel(
            id = record[ID]!!,
            name = record[NAME]!!,
            createdTime = record[CREATED_TIME]!!,
            modifiedTime = record[MODIFIED_TIME]!!,
        )
      }
    }
  }
}
