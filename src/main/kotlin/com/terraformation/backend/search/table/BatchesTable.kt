package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

class BatchesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BATCHES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession", BATCHES.ACCESSION_ID.eq(ACCESSIONS.ID), isRequired = false),
          facilities.asSingleValueSublist("facility", BATCHES.FACILITY_ID.eq(FACILITIES.ID)),
          projects.asSingleValueSublist(
              "project", BATCHES.PROJECT_ID.eq(PROJECTS.ID), isRequired = false),
          species.asSingleValueSublist("species", BATCHES.SPECIES_ID.eq(SPECIES.ID)),
          batchSubLocations.asMultiValueSublist(
              "subLocations", BATCHES.ID.eq(BATCH_SUB_LOCATIONS.BATCH_ID)),
          batchWithdrawals.asMultiValueSublist(
              "withdrawals", BATCHES.ID.eq(BATCH_WITHDRAWALS.BATCH_ID)),
      )
    }
  }

  private val totalQuantityWithdrawnField =
      DSL.coalesce(
          DSL.field(
                  DSL.select(
                          DSL.sum(
                              BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN.plus(
                                      BATCH_WITHDRAWALS.ACTIVE_GROWTH_QUANTITY_WITHDRAWN)
                                  .plus(BATCH_WITHDRAWALS.HARDENING_OFF_QUANTITY_WITHDRAWN)))
                      .from(BATCH_WITHDRAWALS)
                      .where(BATCH_WITHDRAWALS.BATCH_ID.eq(BATCHES.ID)))
              .cast(SQLDataType.BIGINT),
          DSL.value(0))

  // This needs to be lazy-initialized because aliasField() references the list of sublists
  override val fields: List<SearchField> by lazy {
    listOf(
        integerField("activeGrowthQuantity", BATCHES.ACTIVE_GROWTH_QUANTITY),
        dateField("addedDate", BATCHES.ADDED_DATE),
        upperCaseTextField("batchNumber", BATCHES.BATCH_NUMBER),
        integerField("germinatingQuantity", BATCHES.GERMINATING_QUANTITY),
        integerField("germinationRate", BATCHES.GERMINATION_RATE),
        dateField("germinationStartedDate", BATCHES.GERMINATION_STARTED_DATE),
        integerField("hardeningOffQuantity", BATCHES.HARDENING_OFF_QUANTITY),
        idWrapperField("id", BATCHES.ID, ::BatchId),
        idWrapperField("initialBatchId", BATCHES.INITIAL_BATCH_ID, ::BatchId),
        integerField("lossRate", BATCHES.LOSS_RATE),
        textField("notes", BATCHES.NOTES),
        integerField("notReadyQuantity", BATCHES.ACTIVE_GROWTH_QUANTITY),
        dateField("readyByDate", BATCHES.READY_BY_DATE),
        integerField("readyQuantity", BATCHES.READY_QUANTITY),
        dateField("seedsSownDate", BATCHES.SEEDS_SOWN_DATE),
        enumField("substrate", BATCHES.SUBSTRATE_ID),
        textField("substrateNotes", BATCHES.SUBSTRATE_NOTES),
        integerField(
            "totalQuantity",
            BATCHES.READY_QUANTITY.plus(BATCHES.ACTIVE_GROWTH_QUANTITY)
                .plus(BATCHES.HARDENING_OFF_QUANTITY)),
        enumField("treatment", BATCHES.TREATMENT_ID),
        textField("treatmentNotes", BATCHES.TREATMENT_NOTES),
        longField("totalQuantityWithdrawn", totalQuantityWithdrawnField, nullable = false),
        integerField("version", BATCHES.VERSION, localize = false),
    )
  }

  override fun conditionForVisibility(): Condition {
    return BATCHES.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(BATCHES.BATCH_NUMBER, BATCHES.ID)
}
