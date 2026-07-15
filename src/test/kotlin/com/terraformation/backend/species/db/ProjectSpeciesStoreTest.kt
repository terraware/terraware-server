package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.tables.records.ProjectSpeciesRecord
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class ProjectSpeciesStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: ProjectSpeciesStore by lazy { ProjectSpeciesStore(dslContext) }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    projectId = insertProject()
    speciesId = insertSpecies()

    insertOrganizationUser(role = Role.Manager)
  }

  @Nested
  inner class AssignProjects {
    @Test
    fun `creates rows with only the ID columns populated`() {
      store.assignProjects(mapOf(speciesId to setOf(projectId)))

      assertTableEquals(ProjectSpeciesRecord(organizationId, projectId, speciesId))
    }

    @Test
    fun `does not overwrite existing data when a pairing already exists`() {
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Native)

      val before = dslContext.fetch(PROJECT_SPECIES)

      store.assignProjects(mapOf(speciesId to setOf(projectId)))

      assertTableEquals(before)
    }

    @Test
    fun `assigns distinct project sets to different species`() {
      val otherSpeciesId = insertSpecies()
      val otherProjectId = insertProject()

      store.assignProjects(
          mapOf(
              speciesId to setOf(projectId, otherProjectId),
              otherSpeciesId to setOf(otherProjectId),
          )
      )

      assertTableEquals(
          listOf(
              ProjectSpeciesRecord(organizationId, projectId, speciesId),
              ProjectSpeciesRecord(organizationId, otherProjectId, speciesId),
              ProjectSpeciesRecord(organizationId, otherProjectId, otherSpeciesId),
          )
      )
    }

    @Test
    fun `throws exception when species and project are in different organizations`() {
      insertOrganization()
      insertOrganizationUser()
      val otherProjectId = insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.assignProjects(mapOf(speciesId to setOf(otherProjectId)))
      }
    }

    @Test
    fun `throws exception when user cannot update species`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.assignProjects(mapOf(speciesId to setOf(projectId)))
      }
    }

    @Test
    fun `throws exception when a species does not exist`() {
      assertThrows<SpeciesNotFoundException> {
        store.assignProjects(mapOf(SpeciesId(999999) to setOf(projectId)))
      }
    }

    @Test
    fun `throws exception when assignments span multiple organizations`() {
      insertOrganization()
      insertOrganizationUser(role = Role.Manager)
      val otherProjectId = insertProject()
      val otherSpeciesId = insertSpecies()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.assignProjects(
            mapOf(
                speciesId to setOf(projectId),
                otherSpeciesId to setOf(otherProjectId),
            )
        )
      }
    }

    @Test
    fun `throws exception when a project does not exist`() {
      assertThrows<ProjectNotFoundException> {
        store.assignProjects(mapOf(speciesId to setOf(ProjectId(999999))))
      }
    }
  }

  @Nested
  inner class RemoveProjects {
    @Test
    fun `deletes requested associations`() {
      insertProjectSpecies()
      val otherProjectId = insertProject()
      insertProjectSpecies()

      store.removeProjects(mapOf(speciesId to setOf(projectId)))

      assertTableEquals(ProjectSpeciesRecord(organizationId, otherProjectId, speciesId))
    }

    @Test
    fun `is a no-op when the pairing does not exist`() {
      assertDoesNotThrow { store.removeProjects(mapOf(speciesId to setOf(projectId))) }
      assertTableEmpty(PROJECT_SPECIES)
    }

    @Test
    fun `throws exception when user cannot update species`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)
      insertProjectSpecies()

      assertThrows<AccessDeniedException> {
        store.removeProjects(mapOf(speciesId to setOf(projectId)))
      }
    }
  }
}
