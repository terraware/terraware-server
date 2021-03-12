package com.terraformation.seedbank.search

import com.terraformation.seedbank.db.tables.records.AccessionRecord
import com.terraformation.seedbank.db.tables.references.*
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField

/**
 * Defines a table whose columns can be declared as [SearchField] s. The methods here are used in
 * [SearchService] when it dynamically constructs SQL queries based on a search request from a
 * client.
 */
interface SearchTable {
  /** The jOOQ Table object for the table in question. */
  val fromTable: Table<out Record>

  /**
   * Adds a LEFT JOIN clause to a query to connect this table to the accession table. The
   * implementation can assume that the accession table is already present in the SELECT statement.
   */
  fun leftJoinWithAccession(query: SelectJoinStep<out Record>): SelectJoinStep<out Record>

  /**
   * Returns a set of intermediate tables that need to be joined in order to connect this table to
   * the accession table. This supports multi-step chains of foreign keys. For example, if table
   * `foo` has a foreign key column `accession_id` and table `bar` has a foreign key `foo_id`, a
   * query that wants to get a column from `bar` would need to also join with `foo`. In that case,
   * this method would return the [SearchTable] for `foo`.
   *
   * The default implementation returns an empty set, suitable for tables that can be directly
   * joined with the accession table.
   */
  fun dependsOn(): Set<SearchTable> = emptySet()
}

/**
 * Base class for tables that act as children of the accession table. That is, tables that have an
 * foreign key column pointing to the accession table, and hence have a many-to-one relationship
 * with accessions.
 *
 * @param idField The field that contains the accession ID.
 */
abstract class AccessionChildTable(private val idField: TableField<*, Long?>) : SearchTable {
  override val fromTable
    get() = idField.table!!

  override fun leftJoinWithAccession(
      query: SelectJoinStep<out Record>
  ): SelectJoinStep<out Record> {
    return query.leftJoin(idField.table!!).on(idField.eq(ACCESSION.ID))
  }
}

/**
 * Base class for tables that act as parents of the accession table. That is, tables whose primary
 * keys are referenced by foreign key columns in the accession table and thus have a one-to-many
 * relationship with accessions. Typically these are tables that hold the list of choices for
 * enumerated accession fields.
 *
 * @param parentTableIdField The field on the parent table that is referenced by the foreign key on
 * the accession table.
 * @param accessionForeignKeyField The foreign key field on the accession table that references the
 * parent table.
 */
abstract class AccessionParentTable<T>(
    private val parentTableIdField: TableField<*, T?>,
    private val accessionForeignKeyField: TableField<AccessionRecord, T?>
) : SearchTable {
  override val fromTable
    get() = parentTableIdField.table!!

  override fun leftJoinWithAccession(
      query: SelectJoinStep<out Record>
  ): SelectJoinStep<out Record> {
    return query
        .leftJoin(parentTableIdField.table!!)
        .on(parentTableIdField.eq(accessionForeignKeyField))
  }
}

/**
 * Definitions of all the available search tables. This class is never instantiated and acts as a
 * namespace.
 */
class SearchTables {
  object Accession : SearchTable {
    override val fromTable
      get() = ACCESSION

    override fun leftJoinWithAccession(
        query: SelectJoinStep<out Record>
    ): SelectJoinStep<out Record> {
      // No-op; initial query always selects from accession
      return query
    }
  }

  object Bag : AccessionChildTable(BAG.ACCESSION_ID)

  object Geolocation : AccessionChildTable(GEOLOCATION.ACCESSION_ID)

  object Germination : SearchTable {
    override val fromTable
      get() = GERMINATION

    override fun dependsOn(): Set<SearchTable> {
      return setOf(GerminationTest)
    }

    override fun leftJoinWithAccession(
        query: SelectJoinStep<out Record>
    ): SelectJoinStep<out Record> {
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
