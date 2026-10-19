package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class WithdrawalsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = WITHDRAWALS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist("accession", WITHDRAWALS.ACCESSION_ID, ACCESSIONS.ID),
          batches.asSingleValueSublist("batch", WITHDRAWALS.BATCH_ID, BATCHES.ID),
          users.asSingleValueSublist("withdrawnBy", WITHDRAWALS.WITHDRAWN_BY, USERS.ID),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("date", WITHDRAWALS.DATE),
          textField("destination", WITHDRAWALS.DESTINATION),
          longField("estimatedCount", WITHDRAWALS.ESTIMATED_COUNT),
          bigDecimalField("estimatedWeightQuantity", WITHDRAWALS.ESTIMATED_WEIGHT_QUANTITY),
          enumField("estimatedWeightUnits", WITHDRAWALS.ESTIMATED_WEIGHT_UNITS_ID),
          idWrapperField("id", WITHDRAWALS.ID) { WithdrawalId(it) },
          textField("notes", WITHDRAWALS.NOTES),
          enumField("purpose", WITHDRAWALS.PURPOSE_ID),
          idWrapperField("viabilityTestId", WITHDRAWALS.VIABILITY_TEST_ID) { ViabilityTestId(it) },
          *weightFields(
              "",
              WITHDRAWALS.WITHDRAWN_QUANTITY,
              WITHDRAWALS.WITHDRAWN_UNITS_ID,
              WITHDRAWALS.WITHDRAWN_GRAMS,
          ),
      )

  override val visibilitySublistName: String
    get() = "accession"
}
