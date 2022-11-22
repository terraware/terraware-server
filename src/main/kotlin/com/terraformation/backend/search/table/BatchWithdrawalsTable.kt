package com.terraformation.backend.search.table

import com.terraformation.backend.db.nursery.tables.references.BATCH_SUMMARIES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
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
          batches.asSingleValueSublist("batch", BATCH_WITHDRAWALS.BATCH_ID.eq(BATCH_SUMMARIES.ID)),
          batches.asSingleValueSublist(
              "destinationBatch",
              BATCH_WITHDRAWALS.DESTINATION_BATCH_ID.eq(BATCH_SUMMARIES.ID),
              isRequired = false),
          nurseryWithdrawals.asSingleValueSublist(
              "withdrawal", BATCH_WITHDRAWALS.WITHDRAWAL_ID.eq(WITHDRAWAL_SUMMARIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        integerField(
            "germinatingQuantityWithdrawn",
            "Germinating quantity withdrawn",
            BATCH_WITHDRAWALS.GERMINATING_QUANTITY_WITHDRAWN),
        integerField(
            "notReadyQuantityWithdrawn",
            "Not ready quantity withdrawn",
            BATCH_WITHDRAWALS.NOT_READY_QUANTITY_WITHDRAWN),
        integerField(
            "readyQuantityWithdrawn",
            "Ready quantity withdrawn",
            BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN),
    )
  }

  override val inheritsVisibilityFrom: SearchTable = tables.batches

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(BATCH_SUMMARIES).on(BATCH_WITHDRAWALS.BATCH_ID.eq(BATCH_SUMMARIES.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    // We will have already joined with BATCH_SUMMARIES for the visibility check.
    return when (scope) {
      is OrganizationIdScope -> BATCH_SUMMARIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> BATCH_SUMMARIES.FACILITY_ID.eq(scope.facilityId)
    }
  }

  override val defaultOrderFields: List<OrderField<*>> =
      listOf(BATCH_WITHDRAWALS.BATCH_ID, BATCH_WITHDRAWALS.WITHDRAWAL_ID)
}
