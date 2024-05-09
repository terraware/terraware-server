package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AcceleratorSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
    insertParticipant()
    insertProject(countryCode = "KE", participantId = inserted.participantId)

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.canReadInternalTags() } returns true
  }

  @Test
  fun `returns accelerator details`() {
    insertProjectLandUseModelType(landUseModelType = LandUseModelType.Mangroves)
    insertProjectLandUseModelType(landUseModelType = LandUseModelType.Silvopasture)

    insertProjectAcceleratorDetails(
        applicationReforestableLand = 1,
        confirmedReforestableLand = 2.5,
        dealDescription = "description",
        dealStage = DealStage.Phase0DocReview,
        dropboxFolderPath = "/dropbox",
        failureRisk = "failure",
        fileNaming = "naming",
        googleFolderUrl = "https://google.com/",
        investmentThesis = "thesis",
        maxCarbonAccumulation = 5,
        minCarbonAccumulation = 4,
        numCommunities = 2,
        numNativeSpecies = 1,
        perHectareBudget = 6,
        pipeline = Pipeline.AcceleratorProjects,
        projectLead = "lead",
        totalExpansionPotential = 3,
        whatNeedsToBeTrue = "needs",
    )

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf(
                "id",
                "name",
                "acceleratorDetails.applicationReforestableLand",
                "acceleratorDetails.confirmedReforestableLand",
                "acceleratorDetails.dealDescription",
                "acceleratorDetails.dealStage",
                "acceleratorDetails.dropboxFolderPath",
                "acceleratorDetails.failureRisk",
                "acceleratorDetails.fileNaming",
                "acceleratorDetails.googleFolderUrl",
                "acceleratorDetails.investmentThesis",
                "acceleratorDetails.maxCarbonAccumulation",
                "acceleratorDetails.minCarbonAccumulation",
                "acceleratorDetails.numCommunities",
                "acceleratorDetails.numNativeSpecies",
                "acceleratorDetails.perHectareBudget",
                "acceleratorDetails.pipeline",
                "acceleratorDetails.projectLead",
                "acceleratorDetails.totalExpansionPotential",
                "acceleratorDetails.whatNeedsToBeTrue",
                "landUseModelTypes.landUseModelType",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "${inserted.projectId}",
                    "name" to "Project 1",
                    "acceleratorDetails" to
                        mapOf(
                            "applicationReforestableLand" to "1",
                            "confirmedReforestableLand" to "2.5",
                            "dealDescription" to "description",
                            "dealStage" to "Phase 0 (Doc Review)",
                            "dropboxFolderPath" to "/dropbox",
                            "failureRisk" to "failure",
                            "fileNaming" to "naming",
                            "googleFolderUrl" to "https://google.com/",
                            "investmentThesis" to "thesis",
                            "maxCarbonAccumulation" to "5",
                            "minCarbonAccumulation" to "4",
                            "numCommunities" to "2",
                            "numNativeSpecies" to "1",
                            "perHectareBudget" to "6",
                            "pipeline" to "Accelerator Projects",
                            "projectLead" to "lead",
                            "totalExpansionPotential" to "3",
                            "whatNeedsToBeTrue" to "needs",
                        ),
                    "landUseModelTypes" to
                        listOf(
                            mapOf("landUseModelType" to "Mangroves"),
                            mapOf("landUseModelType" to "Silvopasture"),
                        ),
                )))

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `allows internal users to search accelerator organizations they are not in`() {
    every { user.organizationRoles } returns mapOf(organizationId to Role.TerraformationContact)

    insertOrganizationUser()

    val otherAcceleratorOrgId = insertOrganization(2)
    insertOrganizationInternalTag(otherAcceleratorOrgId, InternalTagIds.Accelerator)

    insertOrganization(3)

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))

    val expected =
        SearchResults(
            listOf(
                mapOf("name" to "Organization 1"),
                mapOf("name" to "Organization 2"),
            ))

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `does not allow regular users to search accelerator organizations they are not in`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertOrganizationUser()

    val otherAcceleratorOrgId = insertOrganization(2)
    insertOrganizationInternalTag(otherAcceleratorOrgId, InternalTagIds.Accelerator)

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Organization 1")))

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `allows internal users to search projects in accelerator organizations they are not in`() {
    every { user.organizationRoles } returns mapOf(organizationId to Role.TerraformationContact)

    insertOrganizationUser()

    val nonAcceleratorOrgId = insertOrganization(2)
    insertProject(organizationId = nonAcceleratorOrgId)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Project 1")))

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `does not allow regular users to search projects in accelerator organizations they are not in`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertOrganizationUser()

    val otherAcceleratorOrgId = insertOrganization(2)
    insertOrganizationInternalTag(otherAcceleratorOrgId, InternalTagIds.Accelerator)
    insertProject(organizationId = otherAcceleratorOrgId)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Project 1")))

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `can filter organizations by internal tag to only retrieve accelerator data`() {
    val nonAcceleratorOrgId = insertOrganization(2)

    insertOrganizationUser(organizationId = organizationId, role = Role.TerraformationContact)
    insertOrganizationUser(organizationId = nonAcceleratorOrgId, role = Role.Admin)

    every { user.organizationRoles } returns
        mapOf(organizationId to Role.TerraformationContact, nonAcceleratorOrgId to Role.Admin)

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))
    val condition = FieldNode(prefix.resolve("internalTags_name"), listOf("Accelerator"))

    val expected = SearchResults(listOf(mapOf("name" to "Organization 1")))

    assertJsonEquals(expected, searchService.search(prefix, fields, condition))
  }

  @Test
  fun `does not allow regular organization users to search accelerator details`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertOrganizationUser()
    insertProjectAcceleratorDetails(dealDescription = "description")

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf(
                "id",
                "name",
                "acceleratorDetails.dealDescription",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "${inserted.projectId}",
                    "name" to "Project 1",
                )))

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `does not localize internal-only enum values`() {
    insertProjectAcceleratorDetails(dealStage = DealStage.Phase0DocReview)
    insertProjectLandUseModelType(landUseModelType = LandUseModelType.Monoculture)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf(
                "acceleratorDetails_dealStage",
                "landUseModelTypes_landUseModelType",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "acceleratorDetails_dealStage" to "Phase 0 (Doc Review)",
                    "landUseModelTypes_landUseModelType" to "Monoculture".toGibberish(),
                )))

    val actual = Locales.GIBBERISH.use { searchService.search(prefix, fields, NoConditionNode()) }

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `searches cohorts and participants`() {
    val cohortId1 = insertCohort()
    val participantId2 = insertParticipant(cohortId = cohortId1)
    val projectId2 = insertProject(participantId = participantId2)
    val projectId3 = insertProject(participantId = participantId2)
    val participantId3 = insertParticipant(cohortId = cohortId1)

    // Test setup already inserts a participant+project with no cohort; also insert a second cohort
    insertCohort()
    insertParticipant(cohortId = inserted.cohortId)
    insertProject(participantId = inserted.participantId)

    val prefix = SearchFieldPrefix(searchTables.cohorts)
    val fields =
        listOf(
                "id",
                "name",
                "numParticipants",
                "phase",
                "participants.id",
                "participants.name",
                "participants.projects.id",
                "participants.projects.name",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$cohortId1",
                    "name" to "Cohort 1",
                    "numParticipants" to "2",
                    "phase" to "Phase 0 - Due Diligence",
                    "participants" to
                        listOf(
                            mapOf(
                                "id" to "$participantId2",
                                "name" to "Participant 2",
                                "projects" to
                                    listOf(
                                        mapOf(
                                            "id" to "$projectId2",
                                            "name" to "Project 2",
                                        ),
                                        mapOf(
                                            "id" to "$projectId3",
                                            "name" to "Project 3",
                                        ),
                                    )),
                            mapOf(
                                "id" to "$participantId3",
                                "name" to "Participant 3",
                            ),
                        ),
                )))

    val actual =
        searchService.search(prefix, fields, FieldNode(prefix.resolve("name"), listOf("Cohort 1")))

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `searches cohorts and participants by organization membership for external users`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)

    // Test setup already inserts a participant+project with no cohort
    val cohortId = insertCohort()
    val participantId = insertParticipant(cohortId = inserted.cohortId)
    val projectId =
        insertProject(
            organizationId = inserted.organizationId, participantId = inserted.participantId)

    val otherUser = insertUser(100)
    val otherOrganization = insertOrganization(id = 100, createdBy = otherUser)
    insertOrganizationUser(
        userId = otherUser, organizationId = otherOrganization, role = Role.Admin)
    val otherParticipant = insertParticipant(cohortId = inserted.cohortId)
    insertProject(
        organizationId = otherOrganization, participantId = otherParticipant, createdBy = otherUser)

    val prefix = SearchFieldPrefix(searchTables.cohorts)
    val fields =
        listOf(
                "id",
                "participants.id",
                "participants.projects.id",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$cohortId",
                    "participants" to
                        listOf(
                            mapOf(
                                "id" to "$participantId",
                                "projects" to listOf(mapOf("id" to "$projectId"))),
                        ),
                )))

    val actual = searchService.search(prefix, fields, NoConditionNode())

    assertJsonEquals(expected, actual)
  }
}
