package com.terraformation.backend.search.table

import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class DeliveriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DELIVERIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantings.asMultiValueSublist("plantings", DELIVERIES.ID.eq(PLANTINGS.DELIVERY_ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              DELIVERIES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          nurseryWithdrawals.asSingleValueSublist(
              "withdrawal",
              DELIVERIES.WITHDRAWAL_ID.eq(WITHDRAWAL_SUMMARIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        timestampField("createdTime", DELIVERIES.CREATED_TIME),
        idWrapperField("id", DELIVERIES.ID) { DeliveryId(it) },
        timestampField("reassignedTime", DELIVERIES.REASSIGNED_TIME),
    )
  }

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(DELIVERIES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }
}
