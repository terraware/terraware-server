package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
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

class BatchesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BATCHES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession", BATCHES.ACCESSION_ID.eq(ACCESSIONS.ID), isRequired = false),
          facilities.asSingleValueSublist("facility", BATCHES.FACILITY_ID.eq(FACILITIES.ID)),
          species.asSingleValueSublist("species", BATCHES.SPECIES_ID.eq(SPECIES.ID)),
      )
    }
  }

  // This needs to be lazy-initialized because aliasField() references the list of sublists
  override val fields: List<SearchField> by lazy {
    listOf(
        dateField("addedDate", "Added date", BATCHES.ADDED_DATE, nullable = false),
        upperCaseTextField("batchNumber", "Batch number", BATCHES.BATCH_NUMBER, nullable = false),
        integerField(
            "germinatingQuantity",
            "Germinating quantity",
            BATCHES.GERMINATING_QUANTITY,
            nullable = false),
        textField("notes", "Notes (seedling batch)", BATCHES.NOTES),
        integerField(
            "notReadyQuantity", "Not Ready quantity", BATCHES.NOT_READY_QUANTITY, nullable = false),
        dateField("readyByDate", "Ready By date", BATCHES.READY_BY_DATE),
        integerField("readyQuantity", "Ready quantity", BATCHES.READY_QUANTITY, nullable = false),
    )
  }

  override fun conditionForVisibility(): Condition {
    return BATCHES.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> BATCHES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> BATCHES.FACILITY_ID.eq(scope.facilityId)
    }
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(BATCHES.BATCH_NUMBER, BATCHES.ID)
}
