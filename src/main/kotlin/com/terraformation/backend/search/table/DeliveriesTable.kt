package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class DeliveriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DELIVERIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantings.asMultiValueSublist("plantings", DELIVERIES.ID.eq(PLANTINGS.DELIVERY_ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite", DELIVERIES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)),
          nurseryWithdrawals.asSingleValueSublist(
              "withdrawal", DELIVERIES.WITHDRAWAL_ID.eq(WITHDRAWAL_SUMMARIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        timestampField(
            "createdTime", "Created time (delivery)", DELIVERIES.CREATED_TIME, nullable = false),
        idWrapperField("id", "Delivery ID", DELIVERIES.ID) { DeliveryId(it) },
        timestampField("reassignedTime", "Reassigned time", DELIVERIES.REASSIGNED_TIME),
    )
  }

  override val inheritsVisibilityFrom: SearchTable = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(DELIVERIES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    // We will have already joined with PLANTING_SITE_SUMMARIES for the visibility check.
    return when (scope) {
      is OrganizationIdScope -> PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
