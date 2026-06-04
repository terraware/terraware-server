package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows

class TrackingStatsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: TrackingStatsStore by lazy { TrackingStatsStore(dslContext) }

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser()
  }

  @Nested
  inner class GetSurvivalRate {
    @Test
    fun `returns aggregate survival rates based on site-level rates`() {
      insertSpecies()
      val projectId1 = insertProject()
      val projectId2 = insertProject()

      // Project 1: Sites 1 and 2
      // Project 2: Sites 3 and 4
      //
      // Observation 1: Site 1, site-level survival rate 90, area 5
      // Observation 2: Site 1, site-level survival rate 70, area 10
      // Observation 3: Site 2, site-level survival rate 50, area 20
      // Observation 4: Site 3, site-level survival rate 10, area 40
      // Observation 5: Site 4, no survival rate
      //
      // So the project-level survival rate for project 1 should be
      //
      // (70 * 10 + 50 * 20) / (10 + 20) = 57
      //
      // And the org-level rate should be
      //
      // (70 * 10 + 50 * 20 + 10 * 40) / (10 + 20 + 40) = 30

      insertPlantingSite(projectId = projectId1, x = 0)
      insertObservation(completedTime = Instant.ofEpochSecond(100))
      insertObservationSiteResult(survivalRate = 90, survivalRateArea = 5)
      insertObservation(completedTime = Instant.ofEpochSecond(200))
      insertObservationSiteResult(survivalRate = 70, survivalRateArea = 10)

      insertPlantingSite(projectId = projectId1, x = 0)
      insertObservation(completedTime = Instant.ofEpochSecond(300))
      insertObservationSiteResult(survivalRate = 50, survivalRateArea = 20)

      insertPlantingSite(projectId = projectId2, x = 0)
      insertObservation(completedTime = Instant.ofEpochSecond(400))
      insertObservationSiteResult(survivalRate = 10, survivalRateArea = 40)

      insertPlantingSite(projectId = projectId2, x = 0)
      insertObservation(completedTime = Instant.ofEpochSecond(500))
      insertObservationSiteResult()

      // Other organization's site should be ignored
      insertOrganization()
      insertPlantingSite(x = 0)
      insertObservation(completedTime = Instant.ofEpochSecond(600))
      insertObservationSiteResult(survivalRate = 30, survivalRateArea = 80)

      assertEquals(57, store.getSurvivalRate(projectId1), "Project survival rate")
      assertEquals(30, store.getSurvivalRate(organizationId), "Organization survival rate")
    }

    @Test
    fun `returns null if no sites in scope have survival rates`() {
      val projectId = insertProject()
      insertPlantingSite(projectId = projectId, x = 0)
      insertObservation(completedTime = Instant.ofEpochSecond(100))
      insertObservationSiteResult()

      assertNull(store.getSurvivalRate(projectId), "Project survival rate")
      assertNull(store.getSurvivalRate(organizationId), "Organization survival rate")
    }

    @Test
    fun `throws exception if no permission to read project`() {
      insertOrganization()
      val projectId = insertProject()

      assertThrows<ProjectNotFoundException> { store.getSurvivalRate(projectId) }
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      val otherOrganizationId = insertOrganization()

      assertThrows<OrganizationNotFoundException> { store.getSurvivalRate(otherOrganizationId) }
    }
  }
}
