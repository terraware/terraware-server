package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.OrganizationFeature
import com.terraformation.backend.customer.model.OrganizationFeatureModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
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

  private val emptyOrganizationFeatures: Map<OrganizationFeature, OrganizationFeatureModel> =
      listOf(
          OrganizationFeatureModel(OrganizationFeature.Applications, false),
          OrganizationFeatureModel(OrganizationFeature.Deliverables, false),
          OrganizationFeatureModel(OrganizationFeature.Modules, false),
          OrganizationFeatureModel(OrganizationFeature.Reports, false),
          OrganizationFeatureModel(OrganizationFeature.SeedFundReports, false),
          OrganizationFeatureModel(OrganizationFeature.VirtualWalkthrough, false),
      ).associateBy { it.feature }

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
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No projects",
    )

    val projectId = insertProject()
    insertApplication()

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()
    expectedFeatures[OrganizationFeature.Applications] = OrganizationFeatureModel(
        feature = OrganizationFeature.Applications,
        enabled = true,
        projectIds = setOf(projectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has applications",
    )
  }

  @Test
  fun `checks for projects of modules for the modules feature`() {
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No modules",
    )

    val projectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)

    insertModule()
    insertProjectModule()

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()
    expectedFeatures[OrganizationFeature.Modules] = OrganizationFeatureModel(
        feature = OrganizationFeature.Modules,
        enabled = true,
        projectIds = setOf(projectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has modules",
    )
  }

  @Test
  fun `checks for projects of deliverables in a module for the deliverables feature`() {
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No deliverables",
    )

    val projectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)

    insertModule()
    insertDeliverable()
    insertProjectModule()

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()
    expectedFeatures[OrganizationFeature.Deliverables] = OrganizationFeatureModel(
        feature = OrganizationFeature.Deliverables,
        enabled = true,
        projectIds = setOf(projectId),
    )

    expectedFeatures[OrganizationFeature.Modules] = OrganizationFeatureModel(
        feature = OrganizationFeature.Modules,
        enabled = true,
        projectIds = setOf(projectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has deliverables",
    )
  }

  @Test
  fun `checks for projects of submissions for projects not in a phase for the deliverables feature`() {
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No submission",
    )

    insertModule(phase = AcceleratorPhase.Application)
    insertDeliverable()
    insertProject()
    insertSubmission()
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "Only submission for application module",
    )

    insertModule()
    insertDeliverable()
    val projectId = insertProject()
    insertSubmission()

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()
    expectedFeatures[OrganizationFeature.Deliverables] = OrganizationFeatureModel(
        feature = OrganizationFeature.Deliverables,
        enabled = true,
        projectIds = setOf(projectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has submissions",
    )
  }

  @Test
  fun `checks for projects of reports for projects`() {
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No reports",
    )

    val projectId = insertProject()
    insertProjectReportConfig()
    insertReport()

    val otherProjectId = insertProject()
    insertProjectReportConfig()
    insertReport()

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()
    expectedFeatures[OrganizationFeature.Reports] = OrganizationFeatureModel(
        feature = OrganizationFeature.Reports,
        enabled = true,
        projectIds = setOf(projectId, otherProjectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has reports",
    )
  }

  @Test
  fun `checks for projects of seed fund reports for projects`() {
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No seed fund reports",
    )

    val projectId = insertProject()
    insertSeedFundReport(projectId = projectId)

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()
    expectedFeatures[OrganizationFeature.SeedFundReports] = OrganizationFeatureModel(
        feature = OrganizationFeature.SeedFundReports,
        enabled = true,
        projectIds = setOf(projectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Has seed fund reports",
    )
  }

  @Test
  fun `checks for virtual walkthrough internal tag`() {
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No virtual walkthrough",
    )

    insertOrganizationInternalTag(organizationId = organizationId, tagId = InternalTagId(4))

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()
    expectedFeatures[OrganizationFeature.VirtualWalkthrough] = OrganizationFeatureModel(
        feature = OrganizationFeature.VirtualWalkthrough,
        enabled = true,
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "Virtual walkthrough enabled",
    )
  }

  @Test
  fun `queries with multiple projects`() {
    assertEquals(
        emptyOrganizationFeatures,
        store.listOrganizationFeatureProjects(organizationId),
        "No project added",
    )

    val expectedFeatures = emptyOrganizationFeatures.toMutableMap()

    val applicationProjectId = insertProject(name = "Application project")
    insertApplication()
    insertModule(phase = AcceleratorPhase.Application)
    insertDeliverable()
    insertSubmission()

    expectedFeatures[OrganizationFeature.Applications] = OrganizationFeatureModel(
        feature = OrganizationFeature.Applications,
        enabled = true,
        projectIds = setOf(applicationProjectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One application project",
    )

    val moduleProjectId =
        insertProject(name = "Phase 1 project", phase = AcceleratorPhase.Phase1FeasibilityStudy)
    insertModule(phase = AcceleratorPhase.Phase1FeasibilityStudy)
    insertDeliverable()
    insertProjectModule()

    expectedFeatures[OrganizationFeature.Deliverables] = OrganizationFeatureModel(
        feature = OrganizationFeature.Deliverables,
        enabled = true,
        projectIds = setOf(moduleProjectId),
    )
    expectedFeatures[OrganizationFeature.Modules] = OrganizationFeatureModel(
        feature = OrganizationFeature.Modules,
        enabled = true,
        projectIds = setOf(moduleProjectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One application project and one phase 1 project",
    )

    val reportProjectId = insertProject(name = "Report project")
    insertProjectReportConfig()
    insertReport()

    expectedFeatures[OrganizationFeature.Reports] = OrganizationFeatureModel(
        feature = OrganizationFeature.Reports,
        enabled = true,
        projectIds = setOf(reportProjectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One application project, one phase 1 project, and one report project",
    )

    val seedFundProjectId = insertProject()
    insertSeedFundReport(projectId = seedFundProjectId)
    expectedFeatures[OrganizationFeature.SeedFundReports] = OrganizationFeatureModel(
        feature = OrganizationFeature.SeedFundReports,
        enabled = true,
        projectIds = setOf(seedFundProjectId),
    )

    assertEquals(
        expectedFeatures.toMap(),
        store.listOrganizationFeatureProjects(organizationId),
        "One project for every organization feature",
    )
  }
}
