package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class NurseryWithdrawalsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = WITHDRAWAL_SUMMARIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          batchWithdrawals.asMultiValueSublist(
              "batchWithdrawals", WITHDRAWAL_SUMMARIES.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID)),
          deliveries.asSingleValueSublist(
              "delivery", WITHDRAWAL_SUMMARIES.ID.eq(DELIVERIES.WITHDRAWAL_ID), isRequired = false),
          facilities.asSingleValueSublist(
              "facility", WITHDRAWAL_SUMMARIES.FACILITY_ID.eq(FACILITIES.ID)),
          organizations.asSingleValueSublist(
              "organization", WITHDRAWAL_SUMMARIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField(
              "createdTime",
              "Created time (withdrawal)",
              WITHDRAWAL_SUMMARIES.CREATED_TIME,
              nullable = false),
          // This is exposed as an ID value rather than a sublist because the search code currently
          // doesn't support joining with the same child table twice from the same parent, and
          // there's already a "facility" sublist for the originating facility.
          idWrapperField(
              "destinationFacilityId",
              "Destination facility ID",
              WITHDRAWAL_SUMMARIES.DESTINATION_FACILITY_ID) {
                FacilityId(it)
              },
          textField("destinationName", "Destination name", WITHDRAWAL_SUMMARIES.DESTINATION_NAME),
          booleanField(
              "hasReassignments",
              "Has reassignments",
              WITHDRAWAL_SUMMARIES.HAS_REASSIGNMENTS,
              nullable = false),
          idWrapperField("id", "ID (withdrawal)", WITHDRAWAL_SUMMARIES.ID) { WithdrawalId(it) },
          textField("notes", "Notes (withdrawal)", WITHDRAWAL_SUMMARIES.NOTES),
          textField("plotNames", "Plot names (withdrawal)", WITHDRAWAL_SUMMARIES.PLOT_NAMES),
          enumField(
              "purpose", "Purpose (withdrawal)", WITHDRAWAL_SUMMARIES.PURPOSE_ID, nullable = false),
          longField("totalWithdrawn", "Total withdrawn", WITHDRAWAL_SUMMARIES.TOTAL_WITHDRAWN),
          dateField(
              "withdrawnDate",
              "Date of withdrawal",
              WITHDRAWAL_SUMMARIES.WITHDRAWN_DATE,
              nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return WITHDRAWAL_SUMMARIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    // Accessions table will have already been referenced by joinForVisibility.
    return when (scope) {
      is OrganizationIdScope -> WITHDRAWAL_SUMMARIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> WITHDRAWAL_SUMMARIES.FACILITY_ID.eq(scope.facilityId)
    }
  }

  override val defaultOrderFields: List<OrderField<*>> = listOf(WITHDRAWAL_SUMMARIES.ID)
}
