package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUMMARIES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class BatchesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BATCH_SUMMARIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession", BATCH_SUMMARIES.ACCESSION_ID.eq(ACCESSIONS.ID), isRequired = false),
          facilities.asSingleValueSublist(
              "facility", BATCH_SUMMARIES.FACILITY_ID.eq(FACILITIES.ID)),
          species.asSingleValueSublist("species", BATCH_SUMMARIES.SPECIES_ID.eq(SPECIES.ID)),
          batchWithdrawals.asMultiValueSublist(
              "withdrawals", BATCH_SUMMARIES.ID.eq(BATCH_WITHDRAWALS.BATCH_ID)),
      )
    }
  }

  // This needs to be lazy-initialized because aliasField() references the list of sublists
  override val fields: List<SearchField> by lazy {
    listOf(
        dateField("addedDate", BATCH_SUMMARIES.ADDED_DATE, nullable = false),
        upperCaseTextField("batchNumber", BATCH_SUMMARIES.BATCH_NUMBER, nullable = false),
        integerField("germinatingQuantity", BATCH_SUMMARIES.GERMINATING_QUANTITY, nullable = false),
        idWrapperField("id", BATCH_SUMMARIES.ID, ::BatchId),
        textField("notes", BATCH_SUMMARIES.NOTES),
        integerField("notReadyQuantity", BATCH_SUMMARIES.NOT_READY_QUANTITY, nullable = false),
        dateField("readyByDate", BATCH_SUMMARIES.READY_BY_DATE),
        integerField("readyQuantity", BATCH_SUMMARIES.READY_QUANTITY, nullable = false),
        integerField("totalQuantity", BATCH_SUMMARIES.TOTAL_QUANTITY, nullable = false),
        longField(
            "totalQuantityWithdrawn", BATCH_SUMMARIES.TOTAL_QUANTITY_WITHDRAWN, nullable = false),
        integerField("version", BATCH_SUMMARIES.VERSION, nullable = false, localize = false),
    )
  }

  override fun conditionForVisibility(): Condition {
    return BATCH_SUMMARIES.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return BATCH_SUMMARIES.ORGANIZATION_ID.eq(organizationId)
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(BATCH_SUMMARIES.BATCH_NUMBER, BATCH_SUMMARIES.ID)
}
