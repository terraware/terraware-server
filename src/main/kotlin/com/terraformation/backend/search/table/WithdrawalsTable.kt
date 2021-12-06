package com.terraformation.backend.search.table

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class WithdrawalsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = WITHDRAWALS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist("accession", WITHDRAWALS.ACCESSION_ID.eq(ACCESSIONS.ID)))
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("date", "Date of withdrawal", WITHDRAWALS.DATE, nullable = false),
          textField("destination", "Destination", WITHDRAWALS.DESTINATION),
          gramsField("grams", "Weight of seeds withdrawn (g)", WITHDRAWALS.WITHDRAWN_GRAMS),
          textField("notes", "Notes (withdrawal)", WITHDRAWALS.NOTES),
          enumField("purpose", "Purpose", WITHDRAWALS.PURPOSE_ID),
          bigDecimalField(
              "quantity", "Quantity of seeds withdrawn", WITHDRAWALS.WITHDRAWN_QUANTITY),
          gramsField(
              "remainingGrams",
              "Weight in grams of seeds remaining (withdrawal)",
              WITHDRAWALS.REMAINING_GRAMS),
          bigDecimalField(
              "remainingQuantity",
              "Weight or count of seeds remaining (withdrawal)",
              WITHDRAWALS.REMAINING_QUANTITY),
          enumField(
              "remainingUnits",
              "Units of measurement of quantity remaining (withdrawal)",
              WITHDRAWALS.REMAINING_UNITS_ID),
          enumField(
              "units",
              "Units of measurement of quantity withdrawn",
              WITHDRAWALS.WITHDRAWN_UNITS_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(WITHDRAWALS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
