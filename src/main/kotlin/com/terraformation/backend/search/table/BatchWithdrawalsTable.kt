package com.terraformation.backend.search.table

import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class BatchWithdrawalsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BATCH_WITHDRAWALS.BATCH_WITHDRAWAL_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          batches.asSingleValueSublist("batch", BATCH_WITHDRAWALS.BATCH_ID.eq(BATCHES.ID)),
          batches.asSingleValueSublist(
              "destinationBatch",
              BATCH_WITHDRAWALS.DESTINATION_BATCH_ID.eq(BATCHES.ID),
              isRequired = false),
          nurseryWithdrawals.asSingleValueSublist(
              "withdrawal", BATCH_WITHDRAWALS.WITHDRAWAL_ID.eq(WITHDRAWAL_SUMMARIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        integerField(
            "germinatingQuantityWithdrawn", BATCH_WITHDRAWALS.GERMINATING_QUANTITY_WITHDRAWN),
        integerField(
            "hardeningOffQuantityWithdrawn", BATCH_WITHDRAWALS.HARDENING_OFF_QUANTITY_WITHDRAWN),
        integerField("notReadyQuantityWithdrawn", BATCH_WITHDRAWALS.NOT_READY_QUANTITY_WITHDRAWN),
        integerField("readyQuantityWithdrawn", BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN),
    )
  }

  override val inheritsVisibilityFrom: SearchTable = tables.batches

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(BATCHES).on(BATCH_WITHDRAWALS.BATCH_ID.eq(BATCHES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>> =
      listOf(BATCH_WITHDRAWALS.BATCH_ID, BATCH_WITHDRAWALS.WITHDRAWAL_ID)
}
