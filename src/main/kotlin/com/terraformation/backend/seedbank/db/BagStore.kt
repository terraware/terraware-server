package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.BAGS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@Named
class BagStore(private val dslContext: DSLContext) {
  fun bagNumbersMultiset(idField: Field<AccessionId?> = ACCESSIONS.ID): Field<Set<String>> {
    return DSL.multiset(
            DSL.select(BAGS.BAG_NUMBER)
                .from(BAGS)
                .where(BAGS.ACCESSION_ID.eq(idField))
                .orderBy(BAGS.BAG_NUMBER)
        )
        .convertFrom { result -> result.map { it.get(BAGS.BAG_NUMBER) }.toSet() }
  }

  fun updateBags(
      accessionId: AccessionId,
      existingBagNumbers: Set<String>?,
      desiredBagNumbers: Set<String>?,
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
