package com.terraformation.backend.search.table

import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TEST_RESULTS
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

class ViabilityTestResultsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = VIABILITY_TEST_RESULTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          viabilityTests.asSingleValueSublist(
              "viabilityTest", VIABILITY_TEST_RESULTS.TEST_ID.eq(VIABILITY_TESTS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField(
              "recordingDate",
              "Recording date of viability test result",
              VIABILITY_TEST_RESULTS.RECORDING_DATE),
          integerField(
              "seedsGerminated",
              "Number of seeds germinated",
              VIABILITY_TEST_RESULTS.SEEDS_GERMINATED),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.viabilityTests

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(VIABILITY_TESTS).on(VIABILITY_TEST_RESULTS.TEST_ID.eq(VIABILITY_TESTS.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    // Accessions table will have already been referenced by joinForVisibility.
    return when (scope) {
      is OrganizationIdScope -> ACCESSIONS.facilities.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> ACCESSIONS.FACILITY_ID.eq(scope.facilityId)
    }
  }
}
