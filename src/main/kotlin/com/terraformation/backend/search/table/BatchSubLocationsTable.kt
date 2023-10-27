package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class BatchSubLocationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BATCH_SUB_LOCATIONS.BATCH_SUB_LOCATION_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          batches.asSingleValueSublist(
              "batch", BATCH_SUB_LOCATIONS.BATCH_ID.eq(BATCH_SUMMARIES.ID)),
          subLocations.asSingleValueSublist(
              "subLocation", BATCH_SUB_LOCATIONS.SUB_LOCATION_ID.eq(SUB_LOCATIONS.ID)),
      )
    }
  }

  override val fields = emptyList<SearchField>()

  override val inheritsVisibilityFrom: SearchTable = tables.batches

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(BATCH_SUMMARIES).on(BATCH_SUB_LOCATIONS.BATCH_ID.eq(BATCH_SUMMARIES.ID))
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    // We will have already joined with BATCH_SUMMARIES for the visibility check.
    return BATCH_SUMMARIES.ORGANIZATION_ID.eq(organizationId)
  }

  override val defaultOrderFields: List<OrderField<*>> =
      listOf(BATCH_SUB_LOCATIONS.BATCH_ID, BATCH_SUB_LOCATIONS.SUB_LOCATION_ID)
}
