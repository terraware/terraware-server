package com.terraformation.backend.search.table

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.search.SearchTable
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
class SearchTables(val fuzzySearchOperators: FuzzySearchOperators) {
  val accessionGerminationTestTypes = AccessionGerminationTestTypesTable(this, fuzzySearchOperators)
  val accessions = AccessionsTable(this, fuzzySearchOperators)
  val bags = BagsTable(this, fuzzySearchOperators)
  val collectors = CollectorsTable(this, fuzzySearchOperators)
  val countries = CountriesTable(this, fuzzySearchOperators)
  val countrySubdivisions = CountrySubdivisionsTable(this, fuzzySearchOperators)
  val facilities = FacilitiesTable(this, fuzzySearchOperators)
  val features = FeaturesTable(this, fuzzySearchOperators)
  val geolocations = GeolocationsTable(this, fuzzySearchOperators)
  val germinations = GerminationsTable(this, fuzzySearchOperators)
  val germinationTests = GerminationTestsTable(this, fuzzySearchOperators)
  val layers = LayersTable(this, fuzzySearchOperators)
  val organizations = OrganizationsTable(this, fuzzySearchOperators)
  val organizationUsers = OrganizationUsersTable(this, fuzzySearchOperators)
  val plants = PlantsTable(this, fuzzySearchOperators)
  val projects = ProjectsTable(this, fuzzySearchOperators)
  val projectTypeSelections = ProjectTypeSelectionsTable(fuzzySearchOperators)
  val projectUsers = ProjectUsersTable(this, fuzzySearchOperators)
  val sites = SitesTable(this, fuzzySearchOperators)
  val species = SpeciesTable(this, fuzzySearchOperators)
  val storageLocations = StorageLocationsTable(this, fuzzySearchOperators)
  val users = UsersTable(this, fuzzySearchOperators)
  val withdrawals = WithdrawalsTable(this, fuzzySearchOperators)
}
