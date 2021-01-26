package com.terraformation.seedbank.search

import com.terraformation.seedbank.db.tables.records.AccessionRecord
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.db.tables.references.COLLECTION_EVENT
import com.terraformation.seedbank.db.tables.references.COLLECTOR
import com.terraformation.seedbank.db.tables.references.GERMINATION
import com.terraformation.seedbank.db.tables.references.GERMINATION_TEST
import com.terraformation.seedbank.db.tables.references.SPECIES
import com.terraformation.seedbank.db.tables.references.SPECIES_FAMILY
import com.terraformation.seedbank.db.tables.references.STORAGE_LOCATION
import com.terraformation.seedbank.db.tables.references.WITHDRAWAL
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

interface SearchTable {
  fun addJoin(query: SelectJoinStep<out Record>): SelectJoinStep<out Record>
  fun dependsOn(): Set<SearchTable> = emptySet()
}

abstract class AccessionChildTable(private val idField: TableField<*, Long?>) : SearchTable {
  override fun addJoin(query: SelectJoinStep<out Record>): SelectJoinStep<out Record> {
    return query.leftJoin(idField.table!!).on(idField.eq(ACCESSION.ID))
  }
}

abstract class AccessionParentTable<T>(
    private val parentTableIdField: TableField<*, T?>,
    private val accessionForeignKeyField: TableField<AccessionRecord, T?>
) : SearchTable {
  override fun addJoin(query: SelectJoinStep<out Record>): SelectJoinStep<out Record> {
    return query
        .leftJoin(parentTableIdField.table!!)
        .on(parentTableIdField.eq(accessionForeignKeyField))
  }
}

class SearchTables {
  object Accession : SearchTable {
    override fun addJoin(query: SelectJoinStep<out Record>): SelectJoinStep<out Record> {
      // No-op; initial query always selects from accession
      return query
    }
  }

  object CollectionEvent : AccessionChildTable(COLLECTION_EVENT.ACCESSION_ID)

  object Germination : SearchTable {
    override fun dependsOn(): Set<SearchTable> {
      return setOf(GerminationTest)
    }

    override fun addJoin(query: SelectJoinStep<out Record>): SelectJoinStep<out Record> {
      // We'll already be joined with germination_test
      return query.leftJoin(GERMINATION).on(GERMINATION.TEST_ID.eq(GERMINATION_TEST.ID))
    }
  }

  object GerminationTest : AccessionChildTable(GERMINATION_TEST.ACCESSION_ID)

  object PrimaryCollector :
      AccessionParentTable<Long>(COLLECTOR.ID, ACCESSION.PRIMARY_COLLECTOR_ID)

  object Species : AccessionParentTable<Long>(SPECIES.ID, ACCESSION.SPECIES_ID)

  object SpeciesFamily : AccessionParentTable<Long>(SPECIES_FAMILY.ID, ACCESSION.SPECIES_FAMILY_ID)

  object StorageLocation :
      AccessionParentTable<Long>(STORAGE_LOCATION.ID, ACCESSION.STORAGE_LOCATION_ID)

  object Withdrawal : AccessionChildTable(WITHDRAWAL.ACCESSION_ID)
}
