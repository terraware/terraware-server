package com.terraformation.backend.search.table

import com.terraformation.backend.search.SearchTable
import jakarta.inject.Named
import java.time.Clock
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties

/**
 * Manages the hierarchy of [SearchTable]s. This can be used to look up search tables, either using
 * property references (`searchTables.batches`) or using name indexes (`searchTables["batches"]`).
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
@Named
class SearchTables(clock: Clock) {
  val accessionCollectors = AccessionCollectorsTable(this)
  val accessions = AccessionsTable(this, clock)
  val bags = BagsTable(this)
  val batches = BatchesTable(this)
  val batchSubLocations = BatchSubLocationsTable(this)
  val batchWithdrawals = BatchWithdrawalsTable(this)
  val cohortModules = CohortModulesTable(this)
  val cohorts = CohortsTable(this)
  val countries = CountriesTable(this)
  val countrySubdivisions = CountrySubdivisionsTable(this)
  val deliverables = DeliverablesTable(this)
  val deliveries = DeliveriesTable(this)
  val documents = DocumentsTable(this)
  val documentTemplates = DocumentTemplatesTable(this)
  val draftPlantingSites = DraftPlantingSitesTable(this)
  val events = EventsTable(this)
  val facilities = FacilitiesTable(this)
  val facilityInventories = FacilityInventoriesTable(this)
  val facilityInventoryTotals = FacilityInventoryTotalsTable(this)
  val geolocations = GeolocationsTable(this)
  val internalTags = InternalTagsTable(this)
  val inventories = InventoriesTable(this)
  val modules = ModulesTable(this)
  val monitoringPlots = MonitoringPlotsTable(this)
  val nurserySpeciesProjects = NurserySpeciesProjectsTable(this)
  val nurseryWithdrawals = NurseryWithdrawalsTable(this)
  val observationBiomassDetails = ObservationBiomassDetailsTable(this)
  val observationBiomassQuadratSpecies = ObservationBiomassQuadratSpeciesTable(this)
  val observationBiomassSpecies = ObservationBiomassSpeciesTable(this)
  val observationPlotConditions = ObservationPlotConditionsTable(this)
  val observationPlots = ObservationPlotsTable(this)
  val observations = ObservationsTable(this)
  val organizationInternalTags = OrganizationInternalTagsTable(this)
  val organizations = OrganizationsTable(this)
  val organizationUsers = OrganizationUsersTable(this)
  val participantProjectSpecies = ParticipantProjectSpeciesTable(this)
  val participants = ParticipantsTable(this)
  val plantings = PlantingsTable(this)
  val plantingSeasons = PlantingSeasonsTable(this)
  val plantingSitePopulations = PlantingSitePopulationsTable(this)
  val plantingSites = PlantingSitesTable(this)
  val plantingSubzonePopulations = PlantingSubzonePopulationsTable(this)
  val plantingSubzones = PlantingSubzonesTable(this)
  val plantingZonePopulations = PlantingZonePopulationsTable(this)
  val plantingZones = PlantingZonesTable(this)
  val projectAcceleratorDetails = ProjectAcceleratorDetailsTable(this)
  val projectDeliverables = ProjectDeliverablesTable(this)
  val projectInternalUsers = ProjectInternalUsersTable(this)
  val projectLandUseModelTypes = ProjectLandUseModelTypesTable(this)
  val projectVariableValues = ProjectVariableValuesTable(this)
  val projectVariables = ProjectVariablesTable(this)
  val projects = ProjectsTable(this)
  val recordedTrees = RecordedTreesTable(this)
  val reports = ReportsTable(this)
  val species = SpeciesTable(this)
  val speciesEcosystemTypes = SpeciesEcosystemTypesTable(this)
  val speciesGrowthForms = SpeciesGrowthFormsTable(this)
  val speciesPlantMaterialSourcingMethods = SpeciesPlantMaterialSourcingMethodsTable(this)
  val speciesProblems = SpeciesProblemsTable(this)
  val speciesSuccessionalGroups = SpeciesSuccessionalGroupsTable(this)
  val subLocations = SubLocationsTable(this)
  val users = UsersTable(this)
  val variableSelectOptions = VariableSelectOptionsTable(this)
  val viabilityTestResults = ViabilityTestResultsTable(this)
  val viabilityTests = ViabilityTestsTable(this)
  val withdrawals = WithdrawalsTable(this)

  private val tablesByName: Map<String, SearchTable> by lazy {
    SearchTables::class
        .declaredMemberProperties
        .filter { property ->
          property.visibility == KVisibility.PUBLIC && property.get(this) is SearchTable
        }
        .associate { property -> property.name to property.get(this) as SearchTable }
  }

  operator fun get(name: String): SearchTable? = tablesByName[name]
}
