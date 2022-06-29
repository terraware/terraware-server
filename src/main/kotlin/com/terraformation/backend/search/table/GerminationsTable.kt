package com.terraformation.backend.search.table

import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class GerminationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = GERMINATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          germinationTests.asSingleValueSublist(
              "germinationTest", GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID)))
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField(
              "recordingDate",
              "Recording date of germination test result",
              GERMINATIONS.RECORDING_DATE),
          integerField(
              "seedsGerminated", "Number of seeds germinated", GERMINATIONS.SEEDS_GERMINATED),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.germinationTests

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(GERMINATION_TESTS).on(GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID))
  }
}
