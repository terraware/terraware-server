package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.tables.references.BAGS
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class BagStore(private val dslContext: DSLContext) {
  fun fetchBagNumbers(accessionId: AccessionId): Set<String> {
    return dslContext
        .select(BAGS.BAG_NUMBER)
        .from(BAGS)
        .where(BAGS.ACCESSION_ID.eq(accessionId))
        .orderBy(BAGS.BAG_NUMBER)
        .fetch(BAGS.BAG_NUMBER)
        .filterNotNull()
        .toSet()
  }

  fun updateBags(
      accessionId: AccessionId,
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
            .deleteFrom(BAGS)
            .where(BAGS.ACCESSION_ID.eq(accessionId))
            .and(BAGS.BAG_NUMBER.`in`(deletedBagNumbers))
            .execute()
      }

      addedBagNumbers.forEach { bagNumber ->
        dslContext
            .insertInto(BAGS, BAGS.ACCESSION_ID, BAGS.BAG_NUMBER)
            .values(accessionId, bagNumber)
            .execute()
      }
    }
  }
}
