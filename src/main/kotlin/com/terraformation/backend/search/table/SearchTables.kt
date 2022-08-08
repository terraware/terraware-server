package com.terraformation.backend.search.table

import com.terraformation.backend.search.SearchTable
import java.time.Clock
import javax.annotation.ManagedBean

/**
 * Manages the hierarchy of [SearchTable]s.
 *
 * Search table initialization is complicated by the fact that there are circular references among
 * tables. For example, the `projects` table has a multi-value sublist `sites` that refers to the
 * `sites` table, and the `sites` table has a single-value sublist `project` that refers to the
 * `projects` table.
 *
 * To cope with that, we take a two-phase approach. The [SearchTable] objects are all created here,
 * but their [SearchTable.sublists] values aren't initialized at construction time. Instead, they're
 * lazy-initialized using Kotlin's `by lazy` property delegation mechanism.
 *
 * This fully solves the problem: the only way for application code to access a table is via an
 * instance of this class, and there's no practical way to get an instance of this class that isn't
 * successfully initialized.
 */
@ManagedBean
class SearchTables(clock: Clock) {
  val accessions = AccessionsTable(this, clock)
  val accessionSecondaryCollectors = AccessionSecondaryCollectorsTable(this)
  val bags = BagsTable(this)
  val countries = CountriesTable(this)
  val countrySubdivisions = CountrySubdivisionsTable(this)
  val facilities = FacilitiesTable(this)
  val geolocations = GeolocationsTable(this)
  val organizations = OrganizationsTable(this)
  val organizationUsers = OrganizationUsersTable(this)
  val species = SpeciesTable(this)
  val speciesProblems = SpeciesProblemsTable(this)
  val storageLocations = StorageLocationsTable(this)
  val users = UsersTable(this)
  val viabilityTestResults = ViabilityTestResultsTable(this)
  val viabilityTests = ViabilityTestsTable(this)
  val withdrawals = WithdrawalsTable(this)
}
