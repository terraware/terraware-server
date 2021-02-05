package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.BAG
import com.terraformation.seedbank.services.toSetOrNull
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class BagFetcher(private val dslContext: DSLContext) {
  fun fetchBagNumbers(accessionId: Long): Set<String>? {
    return dslContext
        .select(BAG.LABEL)
        .from(BAG)
        .where(BAG.ACCESSION_ID.eq(accessionId))
        .orderBy(BAG.LABEL)
        .fetch(BAG.LABEL)
        .toSetOrNull()
  }

  fun updateBags(
      accessionId: Long,
      existingBagNumbers: Set<String>?,
      desiredBagNumbers: Set<String>?
  ) {
    if (existingBagNumbers != desiredBagNumbers) {
      val existing = existingBagNumbers ?: emptySet()
      val desired = desiredBagNumbers ?: emptySet()
      val deletedBagNumbers = existing.minus(desired)
      val addedBagNumbers = desired.minus(existing)

      if (deletedBagNumbers.isNotEmpty()) {
        dslContext
            .deleteFrom(BAG)
            .where(BAG.ACCESSION_ID.eq(accessionId))
            .and(BAG.LABEL.`in`(deletedBagNumbers))
            .execute()
      }

      addedBagNumbers.forEach { bagNumber ->
        dslContext
            .insertInto(BAG, BAG.ACCESSION_ID, BAG.LABEL)
            .values(accessionId, bagNumber)
            .execute()
      }
    }
  }
}
