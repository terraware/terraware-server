package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.OrganizationFeature
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class OrganizationFeatureStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private lateinit var organizationId: OrganizationId
  private val store: OrganizationFeatureStore by lazy { OrganizationFeatureStore(dslContext) }

  private val emptyOrganizationFeatureProjects: Map<OrganizationFeature, Set<ProjectId>> =
      mapOf(
          OrganizationFeature.Applications to emptySet(),
          OrganizationFeature.Deliverables to emptySet(),
          OrganizationFeature.Modules to emptySet(),
          OrganizationFeature.Reports to emptySet(),
          OrganizationFeature.SeedFundReports to emptySet(),
      )

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Admin)
  }

  @Test
  fun `throws exception for non-organization user`() {
    deleteOrganizationUser()
    assertThrows<OrganizationNotFoundException> {
      store.listOrganizationFeatureProjects(organizationId)
    }
  }

  @Test
  fun `throws exception for contributors`() {
    deleteOrganizationUser()
    insertOrganizationUser(role = Role.Contributor)
    assertThrows<AccessDeniedException> { store.listOrganizationFeatureProjects(organizationId) }

    deleteOrganizationUser()
    insertOrganizationUser(role = Role.Manager)
    assertDoesNotThrow("Organization manager") {
      store.listOrganizationFeatureProjects(organizationId)
    }
  }

  @Test
  fun `checks for projects of applications for the applications feature`() {
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "No projects",
    )

    val projectId = insertProject()
    insertApplication()

    val expectedFeatureProjects = emptyOrganizationFeatureProjects.toMutableMap()
    expectedFeatureProjects[OrganizationFeature.Applications] = setOf(projectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has applications",
    )
  }

  @Test
  fun `checks for projects of modules for the modules feature`() {
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "No modules",
    )

    val cohortId = insertCohort()
    val projectId = insertProject(cohortId = cohortId)

    insertModule()
    insertCohortModule()

    val expectedFeatureProjects = emptyOrganizationFeatureProjects.toMutableMap()
    expectedFeatureProjects[OrganizationFeature.Modules] = setOf(projectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has modules",
    )
  }

  @Test
  fun `checks for projects of deliverables in a module for the deliverables feature`() {
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "No deliverables",
    )

    val cohortId = insertCohort()
    val projectId = insertProject(cohortId = cohortId)

    insertModule()
    insertDeliverable()
    insertCohortModule()

    val expectedFeatureProjects = emptyOrganizationFeatureProjects.toMutableMap()
    expectedFeatureProjects[OrganizationFeature.Deliverables] = setOf(projectId)
    expectedFeatureProjects[OrganizationFeature.Modules] = setOf(projectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has deliverables",
    )
  }

  @Test
  fun `checks for projects of submissions for projects not in a cohort for the deliverables feature`() {
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "No submission",
    )

    insertModule(phase = CohortPhase.Application)
    insertDeliverable()
    insertProject()
    insertSubmission()
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "Only submission for application module",
    )

    insertModule()
    insertDeliverable()
    val projectId = insertProject()
    insertSubmission()

    val expectedFeatureProjects = emptyOrganizationFeatureProjects.toMutableMap()
    expectedFeatureProjects[OrganizationFeature.Deliverables] = setOf(projectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has submissions",
    )
  }

  @Test
  fun `checks for projects of reports for projects`() {
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "No reports",
    )

    val projectId = insertProject()
    insertProjectReportConfig()
    insertReport()

    val otherProjectId = insertProject()
    insertProjectReportConfig()
    insertReport()

    val expectedFeatureProjects = emptyOrganizationFeatureProjects.toMutableMap()
    expectedFeatureProjects[OrganizationFeature.Reports] = setOf(projectId, otherProjectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has reports",
    )
  }

  @Test
  fun `checks for projects of seed fund reports for projects`() {
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "No seed fund reports",
    )

    val projectId = insertProject()
    insertSeedFundReport(projectId = projectId)

    val expectedFeatureProjects = emptyOrganizationFeatureProjects.toMutableMap()
    expectedFeatureProjects[OrganizationFeature.SeedFundReports] = setOf(projectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has seed fund reports",
    )
  }

  @Test
  fun `queries with multiple projects`() {
    assertEquals(
        emptyOrganizationFeatureProjects,
        store.listOrganizationFeatureProjects(organizationId),
        "No project added",
    )

    val expectedFeatureProjects = emptyOrganizationFeatureProjects.toMutableMap()

    val applicationProjectId = insertProject(name = "Application project")
    insertApplication()
    insertModule(phase = CohortPhase.Application)
    insertDeliverable()
    insertSubmission()

    expectedFeatureProjects[OrganizationFeature.Applications] = setOf(applicationProjectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One application project",
    )

    val cohortId = insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
    val moduleProjectId = insertProject(name = "Cohort project", cohortId = cohortId)
    insertModule(phase = CohortPhase.Phase1FeasibilityStudy)
    insertDeliverable()
    insertCohortModule()

    expectedFeatureProjects[OrganizationFeature.Deliverables] = setOf(moduleProjectId)
    expectedFeatureProjects[OrganizationFeature.Modules] = setOf(moduleProjectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One application project and one cohort phase 1 project",
    )

    val reportProjectId = insertProject(name = "Report project")
    insertProjectReportConfig()
    insertReport()

    expectedFeatureProjects[OrganizationFeature.Reports] = setOf(reportProjectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One application project, one cohort phase 1 project, and one report projects",
    )

    val seedFundProjectId = insertProject()
    insertSeedFundReport(projectId = seedFundProjectId)
    expectedFeatureProjects[OrganizationFeature.SeedFundReports] = setOf(seedFundProjectId)

    assertEquals(
        expectedFeatureProjects.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One project for every organization feature",
    )
  }
}
