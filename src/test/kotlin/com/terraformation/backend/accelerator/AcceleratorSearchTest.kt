package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.NotNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AcceleratorSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertProject(countryCode = "KE", phase = CohortPhase.Phase0DueDiligence)

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
        plantingSitesCql = "tf_accelerator:fid=123",
        projectBoundariesCql = "project_no=5",
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
                "acceleratorDetails.plantingSitesCql",
                "acceleratorDetails.projectBoundariesCql",
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
                            "plantingSitesCql" to "tf_accelerator:fid=123",
                            "projectBoundariesCql" to "project_no=5",
                            "totalExpansionPotential" to "3",
                            "whatNeedsToBeTrue" to "needs",
                        ),
                    "landUseModelTypes" to
                        listOf(
                            mapOf("landUseModelType" to "Mangroves"),
                            mapOf("landUseModelType" to "Silvopasture"),
                        ),
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `allows internal users to search accelerator organizations they are not in`() {
    every { user.organizationRoles } returns mapOf(organizationId to Role.TerraformationContact)

    insertOrganizationUser()

    insertOrganization()
    insertProject(phase = CohortPhase.Phase0DueDiligence)

    insertOrganization()

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))

    val expected =
        SearchResults(
            listOf(
                mapOf("name" to "Organization 1"),
                mapOf("name" to "Organization 2"),
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `does not allow regular users to search accelerator organizations they are not in`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertOrganizationUser()

    insertOrganization()
    insertProject(phase = CohortPhase.Phase0DueDiligence)

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Organization 1")))

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `allows internal users to search projects in accelerator organizations they are not in`() {
    every { user.organizationRoles } returns mapOf(organizationId to Role.TerraformationContact)

    insertOrganizationUser()

    val nonAcceleratorOrgId = insertOrganization()
    insertProject(organizationId = nonAcceleratorOrgId)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Project 1")))

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `does not allow regular users to search projects in accelerator organizations they are not in`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertOrganizationUser()

    val otherAcceleratorOrgId = insertOrganization()
    insertProject(organizationId = otherAcceleratorOrgId, phase = CohortPhase.Phase0DueDiligence)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Project 1")))

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `can filter organizations by project phase to only retrieve accelerator data`() {
    val nonAcceleratorOrgId = insertOrganization()

    insertOrganizationUser(organizationId = organizationId, role = Role.TerraformationContact)
    insertOrganizationUser(organizationId = nonAcceleratorOrgId, role = Role.Admin)

    every { user.organizationRoles } returns
        mapOf(organizationId to Role.TerraformationContact, nonAcceleratorOrgId to Role.Admin)

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))
    val condition = NotNode(FieldNode(prefix.resolve("projects_phase"), listOf(null)))

    val expected = SearchResults(listOf(mapOf("name" to "Organization 1")))

    assertJsonEquals(expected, searchService.search(prefix, fields, mapOf(prefix to condition)))
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
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
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
                )
            )
        )

    val actual =
        Locales.GIBBERISH.use {
          searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))
        }

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `searches modules and deliverables`() {
    val suffix = "${UUID.randomUUID()}"
    val moduleId1 = insertModule(name = "Module 1 $suffix")
    val deliverableId1 = insertDeliverable(name = "Deliverable 1 $suffix")
    val deliverableId2 = insertDeliverable(name = "Deliverable 2 $suffix")
    val cohortId1 = insertCohort(name = "Cohort 1 $suffix")
    val cohortId2 = insertCohort(name = "Cohort 2 $suffix")
    val moduleId2 = insertModule(name = "Module 2 $suffix")
    insertDeliverable(name = "Deliverable 3 $suffix")
    insertCohortModule(cohortId1, moduleId1)
    insertCohortModule(cohortId2, moduleId1)
    insertCohortModule(cohortId2, moduleId2)
    val projectId1a = insertProject(cohortId = cohortId1, phase = CohortPhase.Phase0DueDiligence)
    insertProjectModule(moduleId = moduleId1)
    val projectId1b = insertProject(phase = CohortPhase.Phase0DueDiligence)
    insertProjectModule(moduleId = moduleId1)
    val projectId2 = insertProject(cohortId = cohortId2, phase = CohortPhase.Phase0DueDiligence)
    insertProjectModule(moduleId = moduleId1)
    insertProjectModule(moduleId = moduleId2)
    insertProject(phase = CohortPhase.Phase1FeasibilityStudy)
    insertProjectModule(moduleId = moduleId2)

    val prefix = SearchFieldPrefix(searchTables.modules)
    val fields =
        listOf(
                "id",
                "name",
                "cohortModules.cohort_id",
                "projectModules.project_id",
                "deliverables.id",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$moduleId1",
                    "name" to "Module 1 $suffix",
                    "cohortModules" to
                        listOf(
                            mapOf("cohort_id" to "$cohortId1"),
                            mapOf("cohort_id" to "$cohortId2"),
                        ),
                    "deliverables" to
                        listOf(
                            mapOf("id" to "$deliverableId1"),
                            mapOf("id" to "$deliverableId2"),
                        ),
                    "projectModules" to
                        listOf(
                            mapOf("project_id" to "$projectId1a"),
                            mapOf("project_id" to "$projectId1b"),
                            mapOf("project_id" to "$projectId2"),
                        ),
                )
            ),
            cursor = null,
        )

    val actual =
        searchService.search(
            prefix,
            fields,
            mapOf(
                prefix to FieldNode(prefix.resolve("cohortModules.cohort_id"), listOf("$cohortId1"))
            ),
        )

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `searches cohorts`() {
    val suffix = "${UUID.randomUUID()}"
    val cohortId1 = insertCohort(name = "Cohort 1 $suffix")
    val projectId2 = insertProject(cohortId = cohortId1, phase = CohortPhase.Phase0DueDiligence)
    val projectId3 = insertProject(cohortId = cohortId1, phase = CohortPhase.Phase0DueDiligence)

    // Test setup already inserts a project with no cohort; also insert a second cohort
    insertCohort(name = "Cohort 2 $suffix")
    insertProject(cohortId = inserted.cohortId)

    val prefix = SearchFieldPrefix(searchTables.cohorts)
    val fields =
        listOf(
                "id",
                "name",
                "phase",
                "projects.id",
                "projects.name",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$cohortId1",
                    "name" to "Cohort 1 $suffix",
                    "phase" to "Phase 0 - Due Diligence",
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
                        ),
                ),
            )
        )

    val actual =
        searchService.search(
            prefix,
            fields,
            mapOf(prefix to FieldNode(prefix.resolve("name"), listOf("Cohort 1 $suffix"))),
        )

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `searches cohorts by organization membership for external users`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)

    // Test setup already inserts a project with no cohort
    val cohortId = insertCohort()
    val projectId =
        insertProject(
            cohortId = cohortId,
            organizationId = inserted.organizationId,
        )

    val otherUser = insertUser()
    val otherOrganization = insertOrganization(createdBy = otherUser)
    insertOrganizationUser(
        userId = otherUser,
        organizationId = otherOrganization,
        role = Role.Admin,
    )
    val otherCohort = insertCohort()
    insertProject(
        cohortId = otherCohort,
        organizationId = otherOrganization,
        createdBy = otherUser,
    )

    val prefix = SearchFieldPrefix(searchTables.cohorts)
    val fields = listOf("id", "projects.id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$cohortId",
                    "projects" to listOf(mapOf("id" to "$projectId")),
                )
            )
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }
}
