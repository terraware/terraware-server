package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.tables.records.ProjectSpeciesRecord
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.species.model.ProjectSpeciesOverride
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class ProjectSpeciesStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val store: ProjectSpeciesStore by lazy {
    ProjectSpeciesStore(
        clock,
        dslContext,
        SpeciesNativityCalculator(dslContext),
    )
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    projectId = insertProject()
    speciesId = insertSpecies(scientificName = "Scientific name")

    insertOrganizationUser(role = Role.Manager)

    clock.instant = Instant.ofEpochSecond(1234)
  }

  @Nested
  inner class AssignProjects {
    @Test
    fun `creates rows with only the ID columns populated`() {
      store.assignProjects(mapOf(speciesId to setOf(projectId)))

      assertTableEquals(ProjectSpeciesRecord(organizationId, projectId, speciesId))
    }

    @Test
    fun `uses project locations to calculate species nativity`() {
      val botanicalCountryCode1 = insertBotanicalCountry()
      val botanicalCountryCode2 = insertBotanicalCountry()

      val griisDate = LocalDate.of(2026, 1, 2)
      val wcvpDate = LocalDate.of(2026, 2, 3)
      insertExternalDatasetImport(type = ExternalDatasetType.GRIIS, lastPublicationDate = griisDate)
      insertExternalDatasetImport(type = ExternalDatasetType.WCVP, lastPublicationDate = wcvpDate)
      insertGriisResource(countryCode = "KE")
      insertGriisTaxon(scientificName = "Scientific name", isInvasive = true)
      insertWcvpTaxon(scientificName = "Scientific name")
      insertWcvpDistribution(
          botanicalCountryCode = botanicalCountryCode2,
          speciesNativity = SpeciesNativity.Introduced,
      )

      val projectId1 =
          insertProject(botanicalCountryCode = botanicalCountryCode1, countryCode = "KE")
      val projectId2 =
          insertProject(botanicalCountryCode = botanicalCountryCode2, countryCode = "GH")
      // Project in a location where there are no listings for the species.
      val projectId3 =
          insertProject(botanicalCountryCode = botanicalCountryCode1, countryCode = "TZ")

      store.assignProjects(mapOf(speciesId to setOf(projectId1, projectId2, projectId3)))

      assertTableEquals(
          listOf(
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = projectId1,
                  speciesId = speciesId,
                  calculatedNativityId = SpeciesNativity.Invasive,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  calculatedNativityDatasetDate = griisDate,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = projectId2,
                  speciesId = speciesId,
                  calculatedNativityId = SpeciesNativity.Introduced,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.WCVP,
                  calculatedNativityDatasetDate = wcvpDate,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = projectId3,
                  speciesId = speciesId,
                  calculatedNativityId = SpeciesNativity.Unknown,
              ),
          )
      )
    }

    @Test
    fun `uses organization location to calculate species nativity if only one project`() {
      insertBotanicalCountry()

      dslContext
          .fetchSingle(ORGANIZATIONS, ORGANIZATIONS.ID.eq(organizationId))
          .apply {
            botanicalCountryCode = inserted.botanicalCountryCode
            countryCode = "KE"
          }
          .update()

      insertExternalDatasetImport(
          type = ExternalDatasetType.GRIIS,
          lastPublicationDate = LocalDate.of(2026, 1, 2),
      )
      insertGriisResource(countryCode = "KE")
      insertGriisTaxon(scientificName = "Scientific name", isInvasive = true)

      store.assignProjects(mapOf(speciesId to setOf(projectId)))

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              projectId = projectId,
              speciesId = speciesId,
              calculatedNativityId = SpeciesNativity.Invasive,
              calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              calculatedNativityDatasetDate = LocalDate.of(2026, 1, 2),
          )
      )
    }

    @Test
    fun `does not use organization location to calculate species nativity if multiple projects`() {
      insertBotanicalCountry()

      dslContext
          .fetchSingle(ORGANIZATIONS, ORGANIZATIONS.ID.eq(organizationId))
          .apply {
            botanicalCountryCode = inserted.botanicalCountryCode
            countryCode = "KE"
          }
          .update()

      insertExternalDatasetImport(
          type = ExternalDatasetType.GRIIS,
          lastPublicationDate = LocalDate.of(2026, 1, 2),
      )
      insertGriisResource(countryCode = "KE")
      insertGriisTaxon(scientificName = "Scientific name", isInvasive = true)

      insertProject()

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
  inner class OverridePerProjectData {
    @Test
    fun `overrides nativity for org-level species`() {
      store.overridePerProjectData(
          listOf(
              ProjectSpeciesOverride(
                  overriddenJustification = "Justification",
                  overriddenNativity = SpeciesNativity.Introduced,
                  projectId = null,
                  speciesId = speciesId,
              )
          )
      )

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              overriddenBy = user.userId,
              overriddenJustification = "Justification",
              overriddenNativityId = SpeciesNativity.Introduced,
              overriddenTime = clock.instant,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `overrides nativity for species in project`() {
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Unknown)

      store.overridePerProjectData(
          listOf(
              ProjectSpeciesOverride(
                  overriddenJustification = "Justification",
                  overriddenNativity = SpeciesNativity.Introduced,
                  projectId = projectId,
                  speciesId = speciesId,
              )
          )
      )

      assertTableEquals(
          ProjectSpeciesRecord(
              calculatedNativityDatasetDate = LocalDate.EPOCH,
              calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              calculatedNativityId = SpeciesNativity.Unknown,
              organizationId = organizationId,
              overriddenBy = user.userId,
              overriddenJustification = "Justification",
              overriddenNativityId = SpeciesNativity.Introduced,
              overriddenTime = clock.instant,
              projectId = projectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `throws exception if no permission to update species`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.overridePerProjectData(
            listOf(
                ProjectSpeciesOverride(
                    overriddenJustification = "Justification",
                    overriddenNativity = SpeciesNativity.Introduced,
                    projectId = projectId,
                    speciesId = speciesId,
                )
            )
        )
      }
    }
  }

  @Nested
  inner class RecalculateNativities {
    @Test
    fun `recalculates nativities for project species`() {
      val botanicalCountryCode = insertBotanicalCountry()
      val griisDate = LocalDate.of(2026, 1, 2)
      val wcvpDate = LocalDate.of(2026, 2, 3)
      insertExternalDatasetImport(
          type = ExternalDatasetType.GRIIS,
          lastPublicationDate = griisDate,
      )
      insertExternalDatasetImport(
          type = ExternalDatasetType.WCVP,
          lastPublicationDate = wcvpDate,
      )
      insertGriisResource(countryCode = "KE")
      insertGriisTaxon(scientificName = "Scientific name", isInvasive = true)

      val wcvpSpeciesId = insertSpecies(scientificName = "Other name")
      insertWcvpTaxon(scientificName = "Other name")
      insertWcvpDistribution(
          botanicalCountryCode = botanicalCountryCode,
          speciesNativity = SpeciesNativity.Introduced,
      )

      insertProjectSpecies(
          calculatedNativity = SpeciesNativity.Native,
          calculatedNativityDatasetDate = wcvpDate,
          calculatedNativityDatasetType = ExternalDatasetType.WCVP,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      val locatedProjectId =
          insertProject(botanicalCountryCode = botanicalCountryCode, countryCode = "KE")
      insertProjectSpecies(
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Native,
          overriddenNativityId = SpeciesNativity.Introduced,
      )
      insertProjectSpecies(
          speciesId = wcvpSpeciesId,
          calculatedNativity = SpeciesNativity.Native,
      )

      store.recalculateNativities(locatedProjectId)

      assertTableEquals(
          listOf(
              ProjectSpeciesRecord(
                  calculatedNativityDatasetDate = griisDate,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  calculatedNativityId = SpeciesNativity.Invasive,
                  organizationId = organizationId,
                  projectId = locatedProjectId,
                  speciesId = speciesId,
              ),
              ProjectSpeciesRecord(
                  calculatedNativityDatasetDate = wcvpDate,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.WCVP,
                  calculatedNativityId = SpeciesNativity.Introduced,
                  organizationId = organizationId,
                  projectId = locatedProjectId,
                  speciesId = wcvpSpeciesId,
              ),
              // Other project's species should be left alone.
              ProjectSpeciesRecord(
                  calculatedNativityDatasetDate = wcvpDate,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.WCVP,
                  calculatedNativityId = SpeciesNativity.Native,
                  organizationId = organizationId,
                  overriddenBy = inserted.userId,
                  overriddenJustification = "Justification",
                  overriddenNativityId = SpeciesNativity.Introduced,
                  overriddenTime = Instant.EPOCH,
                  projectId = projectId,
                  speciesId = wcvpSpeciesId,
              ),
          )
      )
    }

    @Test
    fun `sets nativity to unknown when species is not listed in the current location`() {
      val botanicalCountryCode = insertBotanicalCountry()
      val locatedProjectId =
          insertProject(botanicalCountryCode = botanicalCountryCode, countryCode = "TZ")
      insertProjectSpecies(
          projectId = locatedProjectId,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Invasive,
      )

      store.recalculateNativities(locatedProjectId)

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              projectId = locatedProjectId,
              speciesId = speciesId,
              calculatedNativityId = SpeciesNativity.Unknown,
          )
      )
    }

    @Test
    fun `clears nativities when the project has no location`() {
      insertProjectSpecies(
          projectId = projectId,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Invasive,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      store.recalculateNativities(projectId)

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              projectId = projectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `is a no-op when the project has no species`() {
      assertDoesNotThrow { store.recalculateNativities(projectId) }
      assertTableEmpty(PROJECT_SPECIES)
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
