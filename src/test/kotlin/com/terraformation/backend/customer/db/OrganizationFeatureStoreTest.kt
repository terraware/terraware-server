package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.OrganizationFeature
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
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

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Admin)
  }

  @Test
  fun `throws exception for non-organization user or contributors`() {
    deleteOrganizationUser()
    assertThrows<OrganizationNotFoundException>("Not organization member") {
      store.listOrganizationFeatures(organizationId)
    }

    insertOrganizationUser(role = Role.Contributor)
    assertThrows<AccessDeniedException>("Organization contributor") {
      store.listOrganizationFeatures(organizationId)
    }

    deleteOrganizationUser()
    insertOrganizationUser(role = Role.Manager)
    assertDoesNotThrow("Organization manager") { store.listOrganizationFeatures(organizationId) }
  }

  @Test
  fun `checks for existence of applications for the applications feature`() {
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "No applications")

    insertProject()
    insertApplication()
    assertEquals(
        setOf(OrganizationFeature.Applications),
        store.listOrganizationFeatures(organizationId),
        "Has applications")
  }

  @Test
  fun `checks for existence of modules for the modules feature`() {
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "No modules")

    val cohortId = insertCohort()
    val participantId = insertParticipant(cohortId = cohortId)
    insertProject(participantId = participantId)

    insertModule()
    insertCohortModule()

    assertEquals(
        setOf(OrganizationFeature.Modules),
        store.listOrganizationFeatures(organizationId),
        "Has modules")
  }

  @Test
  fun `checks for existence of deliverables in a module for the deliverables feature`() {
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "No deliverables")

    val cohortId = insertCohort()
    val participantId = insertParticipant(cohortId = cohortId)
    insertProject(participantId = participantId)

    insertModule()
    insertDeliverable()
    insertCohortModule()

    assertEquals(
        setOf(OrganizationFeature.Deliverables, OrganizationFeature.Modules),
        store.listOrganizationFeatures(organizationId),
        "Has deliverables")
  }

  @Test
  fun `checks for existence of submissions for projects not in a cohort for the deliverables feature`() {
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "No submission")

    insertModule(phase = CohortPhase.Application)
    insertDeliverable()
    insertProject()
    insertSubmission()
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "Only submission for application module")

    insertModule()
    insertDeliverable()
    insertProject()
    insertSubmission()

    assertEquals(
        setOf(OrganizationFeature.Deliverables),
        store.listOrganizationFeatures(organizationId),
        "Has submissions")
  }

  @Test
  fun `checks for existence of reports for projects`() {
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "No reports")

    insertProject()
    insertProjectReportConfig()
    insertReport()

    assertEquals(
        setOf(OrganizationFeature.Reports),
        store.listOrganizationFeatures(organizationId),
        "Has reports")
  }

  @Test
  fun `checks for existence of seed fund reports for projects`() {
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "No seed fund reports")

    val projectId = insertProject()
    insertSeedFundReport(projectId = projectId)

    assertEquals(
        setOf(OrganizationFeature.SeedFundReports),
        store.listOrganizationFeatures(organizationId),
        "Has seed fund reports")
  }

  @Test
  fun `queries with multiple projects`() {
    assertEquals(
        emptySet<OrganizationFeature>(),
        store.listOrganizationFeatures(organizationId),
        "No project added")

    insertProject(name = "Application project")
    insertApplication()
    insertModule(phase = CohortPhase.Application)
    insertDeliverable()
    insertSubmission()

    assertEquals(
        setOf(OrganizationFeature.Applications),
        store.listOrganizationFeatures(organizationId),
        "One application project")

    val cohortId = insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
    val participantId = insertParticipant(cohortId = cohortId)
    insertProject(name = "Cohort project", participantId = participantId)
    insertModule(phase = CohortPhase.Phase1FeasibilityStudy)
    insertDeliverable()
    insertCohortModule()

    assertEquals(
        setOf(
            OrganizationFeature.Applications,
            OrganizationFeature.Deliverables,
            OrganizationFeature.Modules),
        store.listOrganizationFeatures(organizationId),
        "One application project and one cohort phase 1 project")

    insertProject(name = "Report project")
    insertProjectReportConfig()
    insertReport()

    assertEquals(
        setOf(
            OrganizationFeature.Applications,
            OrganizationFeature.Deliverables,
            OrganizationFeature.Modules,
            OrganizationFeature.Reports),
        store.listOrganizationFeatures(organizationId),
        "One application project, one cohort phase 1 project, and one report projects")
  }
}
