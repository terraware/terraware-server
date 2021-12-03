package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.search.SearchTables

class WithdrawalsNamespace(searchTables: SearchTables, accessionsNamespace: AccessionsNamespace) :
    SearchFieldNamespace() {
  override val sublists: List<SublistField> =
      listOf(
          accessionsNamespace.asSingleValueSublist(
              "accession", WITHDRAWALS.ACCESSION_ID.eq(ACCESSIONS.ID)))

  override val fields: List<SearchField> =
      with(searchTables) {
        listOf(
            withdrawals.dateField("date", "Date of withdrawal", WITHDRAWALS.DATE),
            withdrawals.textField("destination", "Destination", WITHDRAWALS.DESTINATION),
            withdrawals.gramsField(
                "grams", "Weight of seeds withdrawn (g)", WITHDRAWALS.WITHDRAWN_GRAMS),
            withdrawals.textField("notes", "Notes (withdrawal)", WITHDRAWALS.NOTES),
            withdrawals.enumField("purpose", "Purpose", WITHDRAWALS.PURPOSE_ID),
            withdrawals.bigDecimalField(
                "quantity", "Quantity of seeds withdrawn", WITHDRAWALS.WITHDRAWN_QUANTITY),
            withdrawals.gramsField(
                "remainingGrams",
                "Weight in grams of seeds remaining (withdrawal)",
                WITHDRAWALS.REMAINING_GRAMS),
            withdrawals.bigDecimalField(
                "remainingQuantity",
                "Weight or count of seeds remaining (withdrawal)",
                WITHDRAWALS.REMAINING_QUANTITY),
            withdrawals.enumField(
                "remainingUnits",
                "Units of measurement of quantity remaining (withdrawal)",
                WITHDRAWALS.REMAINING_UNITS_ID),
            withdrawals.enumField(
                "units",
                "Units of measurement of quantity withdrawn",
                WITHDRAWALS.WITHDRAWN_UNITS_ID),
        )
      }
}
