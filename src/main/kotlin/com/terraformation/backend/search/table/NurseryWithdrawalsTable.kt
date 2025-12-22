package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
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
              "batchWithdrawals",
              WITHDRAWAL_SUMMARIES.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID),
          ),
          deliveries.asSingleValueSublist(
              "delivery",
              WITHDRAWAL_SUMMARIES.ID.eq(DELIVERIES.WITHDRAWAL_ID),
              isRequired = false,
          ),
          facilities.asSingleValueSublist(
              "facility",
              WITHDRAWAL_SUMMARIES.FACILITY_ID.eq(FACILITIES.ID),
          ),
          organizations.asSingleValueSublist(
              "organization",
              WITHDRAWAL_SUMMARIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", WITHDRAWAL_SUMMARIES.CREATED_TIME),
          // This is exposed as an ID value rather than a sublist because the search code currently
          // doesn't support joining with the same child table twice from the same parent, and
          // there's already a "facility" sublist for the originating facility.
          idWrapperField("destinationFacilityId", WITHDRAWAL_SUMMARIES.DESTINATION_FACILITY_ID) {
            FacilityId(it)
          },
          textField("destinationName", WITHDRAWAL_SUMMARIES.DESTINATION_NAME),
          booleanField("hasReassignments", WITHDRAWAL_SUMMARIES.HAS_REASSIGNMENTS),
          idWrapperField("id", WITHDRAWAL_SUMMARIES.ID) { WithdrawalId(it) },
          textField("notes", WITHDRAWAL_SUMMARIES.NOTES),
          textField("plantingSubzoneNames", WITHDRAWAL_SUMMARIES.SUBSTRATUM_NAMES),
          enumField("purpose", WITHDRAWAL_SUMMARIES.PURPOSE_ID),
          longField("totalWithdrawn", WITHDRAWAL_SUMMARIES.TOTAL_WITHDRAWN),
          dateField("withdrawnDate", WITHDRAWAL_SUMMARIES.WITHDRAWN_DATE),
          dateField("undoesWithdrawalDate", WITHDRAWAL_SUMMARIES.UNDOES_WITHDRAWAL_DATE),
          idWrapperField("undoesWithdrawalId", WITHDRAWAL_SUMMARIES.UNDOES_WITHDRAWAL_ID) {
            WithdrawalId(it)
          },
          dateField("undoneByWithdrawalDate", WITHDRAWAL_SUMMARIES.UNDONE_BY_WITHDRAWAL_DATE),
          idWrapperField("undoneByWithdrawalId", WITHDRAWAL_SUMMARIES.UNDONE_BY_WITHDRAWAL_ID) {
            WithdrawalId(it)
          },
      )

  override fun conditionForVisibility(): Condition {
    return WITHDRAWAL_SUMMARIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }

  override val defaultOrderFields: List<OrderField<*>> = listOf(WITHDRAWAL_SUMMARIES.ID)
}
