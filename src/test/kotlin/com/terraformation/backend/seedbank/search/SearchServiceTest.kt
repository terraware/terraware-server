package com.terraformation.backend.seedbank.search

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFieldPath
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.search.field.AliasField
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach

internal abstract class SearchServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  protected lateinit var searchService: SearchService

  protected val checkedTimeString = "2021-08-18T11:33:55Z"
  protected val checkedTime = Instant.parse(checkedTimeString)
  protected val createdTimeString = "2021-07-15T22:55:12Z"
  protected val createdTime = Instant.parse(createdTimeString)
  protected val modifiedTimeString = "2021-07-16T23:56:55Z"
  protected val modifiedTime = Instant.parse(modifiedTimeString)

  protected val clock = TestClock()

  protected val tables = SearchTables(clock)
  protected val accessionsTable = tables.accessions
  protected val rootPrefix = SearchFieldPrefix(root = accessionsTable)
  protected val accessionIdField = rootPrefix.resolve("id")
  protected val accessionNumberField = rootPrefix.resolve("accessionNumber")
  protected val activeField = rootPrefix.resolve("active")
  protected val bagNumberField = rootPrefix.resolve("bagNumber")
  protected val bagNumberFlattenedField = rootPrefix.resolve("bags_number")
  protected val collectionSiteNameField = rootPrefix.resolve("collectionSiteName")
  protected val facilityIdField = rootPrefix.resolve("facility.id")
  protected val processingNotesField = rootPrefix.resolve("processingNotes")
  protected val receivedDateField = rootPrefix.resolve("receivedDate")
  protected val remainingGramsField = rootPrefix.resolve("remainingGrams")
  protected val speciesNameField = rootPrefix.resolve("speciesName")
  protected val stateField = rootPrefix.resolve("state")
  protected val subLocationNameField = rootPrefix.resolve("subLocation_name")
  protected val plantsCollectedFromField = rootPrefix.resolve("plantsCollectedFrom")
  protected val plantsCollectedFromAlias =
      SearchFieldPath(rootPrefix, AliasField("plantsCollectedFromAlias", plantsCollectedFromField))
  protected val viabilityTestResultsSeedsGerminatedField =
      rootPrefix.resolve("viabilityTests_viabilityTestResults_seedsGerminated")
  protected val viabilityTestSeedsTestedField = rootPrefix.resolve("viabilityTests_seedsTested")
  protected val viabilityTestsTypeField = rootPrefix.resolve("viabilityTests_type")

  protected lateinit var accessionId1: AccessionId
  protected lateinit var accessionId2: AccessionId
  protected lateinit var facilityId: FacilityId
  protected lateinit var organizationId: OrganizationId
  protected lateinit var speciesId1: SpeciesId
  protected lateinit var speciesId2: SpeciesId

  @BeforeEach
  protected fun init() {
    searchService = SearchService(dslContext)

    organizationId = insertOrganization()
    facilityId = insertFacility()

    clock.instant = Instant.parse("2020-06-15T00:00:00.00Z")
    every { user.organizationRoles } returns mapOf(organizationId to Role.Manager)
    every { user.facilityRoles } returns mapOf(facilityId to Role.Manager)

    insertOrganizationUser(role = Role.Manager)

    val now = Instant.now()

    speciesId1 =
        insertSpecies(
            scientificName = "Kousa Dogwood",
            initialScientificName = "Kousa Dogwood",
            checkedTime = checkedTime,
            commonName = "Common 1",
            rare = false,
            createdBy = user.userId,
            createdTime = createdTime,
            modifiedTime = modifiedTime,
            organizationId = organizationId)
    speciesId2 =
        insertSpecies(
            scientificName = "Other Dogwood",
            initialScientificName = "Other Dogwood",
            commonName = "Common 2",
            rare = true,
            conservationCategory = ConservationCategory.Endangered,
            seedStorageBehavior = SeedStorageBehavior.Orthodox,
            createdBy = user.userId,
            createdTime = createdTime,
            modifiedTime = modifiedTime,
            organizationId = organizationId)
    insertSpecies(
        scientificName = "Deleted species",
        initialScientificName = "Deleted species",
        createdBy = user.userId,
        createdTime = now,
        modifiedTime = now,
        deletedTime = now,
        organizationId = organizationId)

    accessionId1 =
        insertAccession(
            AccessionsRow(
                number = "XYZ",
                stateId = AccessionState.InStorage,
                collectedDate = LocalDate.of(2019, 3, 2),
                collectionSiteCity = "city",
                collectionSiteCountryCode = "UG",
                collectionSiteCountrySubdivision = "subdivision",
                collectionSiteLandowner = "landowner",
                collectionSiteName = "siteName",
                collectionSiteNotes = "siteNotes",
                collectionSourceId = CollectionSource.Reintroduced,
                dataSourceId = DataSource.SeedCollectorApp,
                founderId = "plantId",
                speciesId = speciesId1,
                totalWithdrawnCount = 6,
                totalWithdrawnWeightGrams = BigDecimal(5000),
                totalWithdrawnWeightQuantity = BigDecimal(5),
                totalWithdrawnWeightUnitsId = SeedQuantityUnits.Kilograms,
                treesCollectedFrom = 1,
            ))
    accessionId2 =
        insertAccession(
            AccessionsRow(
                number = "ABCDEFG",
                stateId = AccessionState.Processing,
                speciesId = speciesId2,
                treesCollectedFrom = 2))

    accessionCollectorsDao.insert(
        AccessionCollectorsRow(accessionId1, 0, "collector 1"),
        AccessionCollectorsRow(accessionId1, 1, "collector 2"),
        AccessionCollectorsRow(accessionId1, 2, "collector 3"),
    )
  }

  protected fun searchAccessions(
      facilityId: FacilityId,
      fields: Collection<SearchFieldPath>,
      criteria: SearchNode,
      sortOrder: List<SearchSortField> = emptyList(),
      cursor: String? = null,
      limit: Int = Int.MAX_VALUE,
  ): SearchResults {
    val fullFieldList =
        setOf(
            accessionIdField,
            accessionNumberField,
        ) + fields.toSet()
    val facilityIdCriterion = FieldNode(facilityIdField, listOf("$facilityId"))
    val fullCriteria = AndNode(listOf(criteria, facilityIdCriterion))

    return searchService.search(
        rootPrefix, fullFieldList, mapOf(rootPrefix to fullCriteria), sortOrder, cursor, limit)
  }
}
