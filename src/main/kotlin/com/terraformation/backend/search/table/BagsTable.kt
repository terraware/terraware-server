package com.terraformation.backend.search.table

import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.BAGS
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class BagsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BAGS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist("accession", BAGS.ACCESSION_ID.eq(ACCESSIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("number", "Bag number", BAGS.BAG_NUMBER),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(BAGS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    // Accessions table will have already been referenced by joinForVisibility.
    return when (scope) {
      is OrganizationIdScope -> ACCESSIONS.facilities.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> ACCESSIONS.FACILITY_ID.eq(scope.facilityId)
    }
  }
}
