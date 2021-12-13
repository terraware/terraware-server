package com.terraformation.backend.search.namespace

import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SearchTables
import javax.annotation.ManagedBean

/**
 * Manages the hierarchy of [SearchFieldNamespace]s.
 *
 * Namespace initialization is complicated by the fact that there are circular references among
 * namespaces. For example, the `projects` namespace has a multi-value sublist `sites` that refers
 * to the `sites` namespace, and the `sites` namespace has a single-value sublist `project` that
 * refers to the `projects` namespace.
 *
 * To cope with that, we take a two-phase approach. The [SearchFieldNamespace] objects are all
 * created here, but their [SearchFieldNamespace.sublists] values aren't initialized at construction
 * time. Instead, they're lazy-initialized using Kotlin's `by lazy` property delegation mechanism.
 *
 * This fully solves the problem: the only way for application code to access a namespace is via an
 * instance of this class, and there's no practical way to get an instance of this class that isn't
 * successfully initialized.
 */
@ManagedBean
class SearchFieldNamespaces(val searchTables: SearchTables) {
  val accessionGerminationTestTypes = AccessionGerminationTestTypesNamespace(this)
  val accessions = AccessionsNamespace(this)
  val bags = BagsNamespace(this)
  val collectors = CollectorsNamespace(this)
  val countries = CountriesNamespace(this)
  val countrySubdivisions = CountrySubdivisionsNamespace(this)
  val facilities = FacilitiesNamespace(this)
  val features = FeaturesNamespace(this)
  val geolocations = GeolocationsNamespace(this)
  val germinations = GerminationsNamespace(this)
  val germinationTests = GerminationTestsNamespace(this)
  val layers = LayersNamespace(this)
  val organizations = OrganizationsNamespace(this)
  val plants = PlantsNamespace(this)
  val projects = ProjectsNamespace(this)
  val sites = SitesNamespace(this)
  val species = SpeciesNamespace(this)
  val storageLocations = StorageLocationsNamespace(this)
  val withdrawals = WithdrawalsNamespace(this)
}
