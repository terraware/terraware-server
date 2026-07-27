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

  private val griisDate = LocalDate.of(2026, 1, 2)
  private val wcvpDate = LocalDate.of(2026, 2, 3)

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
    fun `uses project locations to calculate pending species nativity`() {
      val botanicalCountryCode1 = insertBotanicalCountry()
      val botanicalCountryCode2 = insertBotanicalCountry()

      insertGriisInvasiveListing()
      insertExternalDatasetImport(type = ExternalDatasetType.WCVP, lastPublicationDate = wcvpDate)
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
                  pendingNativityId = SpeciesNativity.Invasive,
                  pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  pendingNativityDatasetDate = griisDate,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = projectId2,
                  speciesId = speciesId,
                  pendingNativityId = SpeciesNativity.Introduced,
                  pendingNativityDatasetTypeId = ExternalDatasetType.WCVP,
                  pendingNativityDatasetDate = wcvpDate,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = projectId3,
                  speciesId = speciesId,
                  pendingNativityId = SpeciesNativity.Unknown,
              ),
          )
      )
    }

    @Test
    fun `uses organization location to calculate pending species nativity if only one project`() {
      setOrganizationLocation()
      insertGriisInvasiveListing()

      store.assignProjects(mapOf(speciesId to setOf(projectId)))

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              projectId = projectId,
              speciesId = speciesId,
              pendingNativityId = SpeciesNativity.Invasive,
              pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              pendingNativityDatasetDate = griisDate,
          )
      )
    }

    @Test
    fun `does not use organization location to calculate pending nativity if multiple projects`() {
      setOrganizationLocation()
      insertGriisInvasiveListing()

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
    fun `removes organization-level nativity on project assignment`() {
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Invasive, projectId = null)

      store.assignProjects(mapOf(speciesId to setOf(projectId)))

      assertTableEquals(ProjectSpeciesRecord(organizationId, projectId, speciesId))
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
  inner class RecalculateNativitiesForProject {
    @Test
    fun `accepts recalculated nativities when autoAccept is set`() {
      val botanicalCountryCode = insertBotanicalCountry()
      insertGriisInvasiveListing()
      insertExternalDatasetImport(type = ExternalDatasetType.WCVP, lastPublicationDate = wcvpDate)

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
      // A species whose only existing nativity is a pending one should get an accepted nativity.
      insertProjectSpecies(
          speciesId = wcvpSpeciesId,
          pendingNativity = SpeciesNativity.Native,
      )

      store.recalculateNativities(locatedProjectId, autoAccept = true)

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
    fun `leaves recalculated nativities pending by default`() {
      val botanicalCountryCode = insertBotanicalCountry()
      insertGriisInvasiveListing()

      val locatedProjectId =
          insertProject(botanicalCountryCode = botanicalCountryCode, countryCode = "KE")
      insertProjectSpecies(projectId = locatedProjectId, speciesId = speciesId)

      val otherSpeciesId = insertSpecies(scientificName = "Scientific name 2")
      insertProjectSpecies(
          projectId = locatedProjectId,
          speciesId = otherSpeciesId,
          calculatedNativity = SpeciesNativity.Native,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      store.recalculateNativities(locatedProjectId)

      assertTableEquals(
          listOf(
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  pendingNativityDatasetDate = griisDate,
                  pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  pendingNativityId = SpeciesNativity.Invasive,
                  projectId = locatedProjectId,
                  speciesId = speciesId,
              ),
              // An existing nativity and override are left alone when autoAccept isn't set.
              ProjectSpeciesRecord(
                  calculatedNativityDatasetDate = LocalDate.EPOCH,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  calculatedNativityId = SpeciesNativity.Native,
                  organizationId = organizationId,
                  overriddenBy = inserted.userId,
                  overriddenJustification = "Justification",
                  overriddenNativityId = SpeciesNativity.Introduced,
                  overriddenTime = Instant.EPOCH,
                  pendingNativityId = SpeciesNativity.Unknown,
                  projectId = locatedProjectId,
                  speciesId = otherSpeciesId,
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

      store.recalculateNativities(locatedProjectId, autoAccept = true)

      assertTableEquals(
          ProjectSpeciesRecord(
              calculatedNativityId = SpeciesNativity.Unknown,
              organizationId = organizationId,
              projectId = locatedProjectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `clears nativities and overrides when the project has no location`() {
      insertProjectSpeciesWithAllNativities()

      store.recalculateNativities(projectId, autoAccept = true)

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              projectId = projectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `clears pending nativity but retains existing values when the project has no location`() {
      insertProjectSpeciesWithAllNativities()

      store.recalculateNativities(projectId)

      assertTableEquals(
          ProjectSpeciesRecord(
              calculatedNativityDatasetDate = LocalDate.EPOCH,
              calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              calculatedNativityId = SpeciesNativity.Invasive,
              organizationId = organizationId,
              overriddenBy = inserted.userId,
              overriddenJustification = "Justification",
              overriddenNativityId = SpeciesNativity.Introduced,
              overriddenTime = Instant.EPOCH,
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
  inner class RecalculateNativitiesForOrganization {
    @Test
    fun `accepts recalculated nativity if organization has no projects`() {
      val botanicalCountryCode = insertBotanicalCountry()
      insertGriisInvasiveListing()

      val locatedOrganizationId =
          insertOrganization(botanicalCountryCode = botanicalCountryCode, countryCode = "KE")
      // Species without a project is still tracked in project_species with a null project ID.
      val orgSpeciesId = insertSpecies(scientificName = "Scientific name")
      insertProjectSpecies(
          projectId = null,
          calculatedNativity = SpeciesNativity.Native,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      store.recalculateNativities(locatedOrganizationId, autoAccept = true)

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = locatedOrganizationId,
              projectId = null,
              speciesId = orgSpeciesId,
              calculatedNativityDatasetDate = griisDate,
              calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              calculatedNativityId = SpeciesNativity.Invasive,
          )
      )
    }

    @Test
    fun `recalculates nativities based on org location for organization with one project`() {
      setOrganizationLocation()
      insertGriisInvasiveListing()

      // This species is already tied to the org's single project; it should be updated in place
      // rather than getting an additional row with a null project ID.
      insertProjectSpecies(
          projectId = projectId,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Native,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      // This species already has an org-level row, which should be updated in place.
      val nonProjectSpeciesId = insertSpecies(scientificName = "Scientific 2")
      insertProjectSpecies(projectId = null)

      // This species isn't listed in project_species yet; it should get a new null-project row.
      val unlistedSpeciesId = insertSpecies(scientificName = "Scientific 3")

      store.recalculateNativities(organizationId, autoAccept = true)

      assertTableEquals(
          listOf(
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = projectId,
                  speciesId = speciesId,
                  calculatedNativityDatasetDate = griisDate,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  calculatedNativityId = SpeciesNativity.Invasive,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = null,
                  speciesId = nonProjectSpeciesId,
                  calculatedNativityId = SpeciesNativity.Unknown,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  projectId = null,
                  speciesId = unlistedSpeciesId,
                  calculatedNativityId = SpeciesNativity.Unknown,
              ),
          )
      )
    }

    @Test
    fun `leaves recalculated nativities pending by default`() {
      setOrganizationLocation()
      insertGriisInvasiveListing()

      insertProjectSpecies(
          projectId = projectId,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Native,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      val unlistedSpeciesId = insertSpecies(scientificName = "Scientific 2")

      store.recalculateNativities(organizationId)

      assertTableEquals(
          listOf(
              ProjectSpeciesRecord(
                  calculatedNativityDatasetDate = LocalDate.EPOCH,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  calculatedNativityId = SpeciesNativity.Native,
                  organizationId = organizationId,
                  overriddenBy = inserted.userId,
                  overriddenJustification = "Justification",
                  overriddenNativityId = SpeciesNativity.Introduced,
                  overriddenTime = Instant.EPOCH,
                  pendingNativityDatasetDate = griisDate,
                  pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  pendingNativityId = SpeciesNativity.Invasive,
                  projectId = projectId,
                  speciesId = speciesId,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  pendingNativityId = SpeciesNativity.Unknown,
                  projectId = null,
                  speciesId = unlistedSpeciesId,
              ),
          )
      )
    }

    @Test
    fun `clears nativities and overrides when organization has no location`() {
      insertProjectSpeciesWithAllNativities()

      store.recalculateNativities(organizationId, autoAccept = true)

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              projectId = projectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `is a no-op if organization has more than one project`() {
      insertProject()

      insertProjectSpecies(
          projectId = projectId,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Native,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      val before = dslContext.fetch(PROJECT_SPECIES)

      store.recalculateNativities(organizationId)

      assertTableEquals(before)
    }
  }

  @Nested
  inner class ResetNativities {
    @Test
    fun `leaves recalculated nativities pending for every project the species is assigned to`() {
      val botanicalCountryCode = insertBotanicalCountry()
      insertGriisInvasiveListing()
      insertExternalDatasetImport(type = ExternalDatasetType.WCVP, lastPublicationDate = wcvpDate)
      insertWcvpTaxon(scientificName = "Scientific name")
      insertWcvpDistribution(
          botanicalCountryCode = botanicalCountryCode,
          speciesNativity = SpeciesNativity.Native,
      )

      val griisProjectId =
          insertProject(botanicalCountryCode = botanicalCountryCode, countryCode = "KE")
      insertProjectSpecies(
          projectId = griisProjectId,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Unknown,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      // Other species in the same project should be left alone.
      val otherSpeciesId = insertSpecies(scientificName = "Other name")
      insertProjectSpecies(
          projectId = griisProjectId,
          speciesId = otherSpeciesId,
          calculatedNativity = SpeciesNativity.Native,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      // The species doesn't have a nativity in this project yet.
      val wcvpProjectId =
          insertProject(botanicalCountryCode = botanicalCountryCode, countryCode = "GH")
      insertProjectSpecies(projectId = wcvpProjectId, speciesId = speciesId)

      store.resetNativities(speciesId)

      assertTableEquals(
          listOf(
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  pendingNativityDatasetDate = griisDate,
                  pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  pendingNativityId = SpeciesNativity.Invasive,
                  projectId = griisProjectId,
                  speciesId = speciesId,
              ),
              ProjectSpeciesRecord(
                  calculatedNativityDatasetDate = LocalDate.EPOCH,
                  calculatedNativityDatasetTypeId = ExternalDatasetType.GRIIS,
                  calculatedNativityId = SpeciesNativity.Native,
                  organizationId = organizationId,
                  overriddenBy = inserted.userId,
                  overriddenJustification = "Justification",
                  overriddenNativityId = SpeciesNativity.Introduced,
                  overriddenTime = Instant.EPOCH,
                  projectId = griisProjectId,
                  speciesId = otherSpeciesId,
              ),
              ProjectSpeciesRecord(
                  organizationId = organizationId,
                  pendingNativityDatasetDate = wcvpDate,
                  pendingNativityDatasetTypeId = ExternalDatasetType.WCVP,
                  pendingNativityId = SpeciesNativity.Native,
                  projectId = wcvpProjectId,
                  speciesId = speciesId,
              ),
          )
      )
    }

    @Test
    fun `recalculates organization-level nativity when species has no project`() {
      setOrganizationLocation()
      insertGriisInvasiveListing()

      insertProjectSpecies(
          projectId = null,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Unknown,
          overriddenNativityId = SpeciesNativity.Introduced,
      )

      store.resetNativities(speciesId)

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              pendingNativityDatasetDate = griisDate,
              pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              pendingNativityId = SpeciesNativity.Invasive,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `clears nativities when the project has no location`() {
      insertProjectSpeciesWithAllNativities()

      store.resetNativities(speciesId)

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              projectId = projectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `throws exception when user cannot update species`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.resetNativities(speciesId) }
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

  /** Inserts a project species row that has accepted, overridden, and pending nativities. */
  private fun insertProjectSpeciesWithAllNativities() {
    insertProjectSpecies(
        calculatedNativity = SpeciesNativity.Invasive,
        overriddenNativityId = SpeciesNativity.Introduced,
        pendingNativity = SpeciesNativity.Native,
        projectId = projectId,
        speciesId = speciesId,
    )
  }

  /** Publishes a GRIIS listing of a species as invasive in Kenya. */
  private fun insertGriisInvasiveListing(scientificName: String = "Scientific name") {
    insertExternalDatasetImport(type = ExternalDatasetType.GRIIS, lastPublicationDate = griisDate)
    insertGriisResource(countryCode = "KE")
    insertGriisTaxon(scientificName = scientificName, isInvasive = true)
  }

  /** Gives the organization a location so its species' nativities can be calculated. */
  private fun setOrganizationLocation() {
    insertBotanicalCountry()

    dslContext
        .fetchSingle(ORGANIZATIONS, ORGANIZATIONS.ID.eq(organizationId))
        .apply {
          botanicalCountryCode = inserted.botanicalCountryCode
          countryCode = "KE"
        }
        .update()
  }
}
