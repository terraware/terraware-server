package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class WithdrawalsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          accessions.asSingleValueSublist("accession", WITHDRAWALS.ACCESSION_ID.eq(ACCESSIONS.ID)))
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.withdrawals) {
        listOf(
            dateField("date", "Date of withdrawal", WITHDRAWALS.DATE),
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
      }
}
