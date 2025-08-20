package com.terraformation.backend.accelerator.migration

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_HISTORIES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_SCORES
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationsRow
import com.terraformation.backend.importer.processCsvFile
import jakarta.inject.Named
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ProjectSetUpImporter(
    private val applicationStore: ApplicationStore,
    private val clock: InstantSource,
    private val countriesDao: CountriesDao,
    private val dslContext: DSLContext,
    private val organizationStore: OrganizationStore,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val projectStore: ProjectStore,
    private val systemUser: SystemUser,
) {

  fun importCsv(inputStream: InputStream) {
    requirePermissions { createEntityWithOwner(systemUser.userId) }

    val userId = currentUser().userId

    systemUser.run {
      dslContext.transaction { _ ->
        lateinit var columnNames: Map<Int, String>

        processCsvFile(inputStream, skipHeaderRow = false) { values, rowNumber, addError ->
          try {
            if (rowNumber == 1) {
              columnNames =
                  values
                      .mapIndexed { index, name -> name?.let { index to it } }
                      .filterNotNull()
                      .toMap()
            }

            // Rows 2 and 3 are additional headers, so only process data starting at row 4.
            if (rowNumber < 4) {
              return@processCsvFile
            }

            val valuesByName =
                values
                    .mapIndexed { index, value -> value?.let { columnNames[index]!! to it } }
                    .filterNotNull()
                    .toMap()

            val dealName = getMandatory(valuesByName, "Project Name")
            val countryCode = fetchCountryCode(dealName)

            // We'll use the non-country part of the deal name as the name for the org, project,
            // etc.
            val projectName = dealName.substring(4)

            val application: ExistingApplicationModel
            val project: ExistingProjectModel

            // If we already have an application with the deal name, use it to get the existing
            // organization and project IDs even if the spreadsheet doesn't have them listed.
            val existingApplication = applicationStore.fetchOneByInternalName(dealName)

            if (existingApplication != null) {
              application = existingApplication
              project = projectStore.fetchOneById(existingApplication.projectId)
            } else {
              val organizationIdFromCsv =
                  valuesByName["Organization ID"]?.let { OrganizationId(it) }
              val organization =
                  if (organizationIdFromCsv != null) {
                    organizationStore.fetchOneById(organizationIdFromCsv)
                  } else {
                    organizationStore.createWithAdmin(
                        OrganizationsRow(countryCode = countryCode, name = projectName),
                        ownerUserId = userId,
                    )
                  }

              val projectIdFromCsv = valuesByName["Project ID"]?.let { ProjectId(it) }
              project =
                  if (projectIdFromCsv != null) {
                    projectStore.fetchOneById(projectIdFromCsv)
                  } else {
                    val projectId =
                        projectStore.create(
                            NewProjectModel(
                                id = null,
                                name = projectName,
                                organizationId = organization.id,
                            )
                        )
                    projectStore.fetchOneById(projectId)
                  }

              application = applicationStore.create(project.id)

              // The default internal name has no country code.
              with(APPLICATIONS) {
                dslContext
                    .update(APPLICATIONS)
                    .set(INTERNAL_NAME, dealName)
                    .where(ID.eq(application.id))
                    .execute()
              }
            }

            updateApplicationStatus(valuesByName, application)
            updateProjectAcceleratorDetails(valuesByName, project, countryCode)
            updateScores(valuesByName, project)
          } catch (e: Exception) {
            addError(e.message ?: e.toString())
          }
        }
      }
    }
  }

  private fun updateScores(valuesByName: Map<String, String>, project: ExistingProjectModel) {
    val columnNamePrefixes =
        listOf(
            "Phase 0: " to CohortPhase.Phase0DueDiligence,
            "Phase 1: " to CohortPhase.Phase1FeasibilityStudy,
        )

    val userId = currentUser().userId
    val now = clock.instant()

    columnNamePrefixes.forEach { (phasePrefix, phase) ->
      ScoreCategory.entries.forEach { category ->
        val prefix = "$phasePrefix${category.jsonValue}"
        val score = getInt(valuesByName, "$prefix Score")

        // Qualitative column names sometimes include the word "Score" and sometimes not.
        val qualitative =
            valuesByName["$prefix Qualitative"] ?: valuesByName["$prefix Score Qualitative"]

        if (score != null || qualitative != null) {
          // Set directly rather than using ProjectScoreStore because some of the data would fail
          // validation.
          with(PROJECT_SCORES) {
            dslContext
                .insertInto(PROJECT_SCORES)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(PHASE_ID, phase)
                .set(PROJECT_ID, project.id)
                .set(QUALITATIVE, qualitative)
                .set(SCORE, score)
                .set(SCORE_CATEGORY_ID, category)
                .onConflict()
                .doUpdate()
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(QUALITATIVE, qualitative)
                .set(SCORE, score)
                .execute()
          }
        }
      }
    }
  }

  private fun updateProjectAcceleratorDetails(
      valuesByName: Map<String, String>,
      project: ExistingProjectModel,
      countryCode: String,
  ) {
    val landUseModelTypes =
        valuesByName["Land Use Model Type"]
            ?.let { typesString ->
              typesString.split(";").map { LandUseModelType.forJsonValue(it.trim()) }
            }
            ?.toSet()

    projectAcceleratorDetailsService.update(project.id) { model ->
      model.copy(
          annualCarbon = getBigDecimal(valuesByName, "Annual Carbon (t)"),
          applicationReforestableLand =
              getBigDecimal(valuesByName, "Application Reforestable Land (ha)"),
          carbonCapacity = getBigDecimal(valuesByName, "Carbon Capacity (tCO2/ha)"),
          confirmedReforestableLand = getBigDecimal(valuesByName, "TF Reforestable Land (ha)"),
          countryCode = countryCode,
          dealDescription = valuesByName["Deal Description"],
          dropboxFolderPath = valuesByName["Dropbox Path"],
          failureRisk = valuesByName["Failure Risk"],
          fileNaming = getMandatory(valuesByName, "Project Name"),
          googleFolderUrl = valuesByName["GDrive URL"]?.let { URI(it) },
          hubSpotUrl = valuesByName["HubSpot Link"]?.let { URI(it) },
          investmentThesis = valuesByName["Investment Thesis"],
          landUseModelTypes = landUseModelTypes ?: emptySet(),
          maxCarbonAccumulation =
              getBigDecimal(valuesByName, "Max Carbon Accumulation (CO2/ha/yr)"),
          minCarbonAccumulation =
              getBigDecimal(valuesByName, "Min Carbon Accumulation (CO2/ha/yr)"),
          numNativeSpecies = getInt(valuesByName, "Number of native species"),
          perHectareBudget = getBigDecimal(valuesByName, "Per Hectare Estimated Budget (USD)"),
          totalCarbon = getBigDecimal(valuesByName, "Total Carbon (t)"),
          totalExpansionPotential = getBigDecimal(valuesByName, "Total Expansion Potential (ha)"),
          whatNeedsToBeTrue = valuesByName["What Needs To Be True"],
      )
    }
  }

  private fun updateApplicationStatus(
      valuesByName: Map<String, String>,
      application: ExistingApplicationModel,
  ) {
    val applicationStatus =
        ApplicationStatus.forJsonValue(getMandatory(valuesByName, "Application Status"))
    val submissionTime =
        valuesByName["Application Submission Date"]?.let {
          LocalDate.parse(it).atTime(0, 0).atZone(ZoneOffset.UTC).toInstant()
        } ?: clock.instant()

    if (application.status != applicationStatus || application.modifiedTime != submissionTime) {
      // Update directly rather than using ApplicationStore; we don't want to send notifications
      // about status changes.
      dslContext
          .update(APPLICATIONS)
          .set(APPLICATIONS.APPLICATION_STATUS_ID, applicationStatus)
          .set(APPLICATIONS.MODIFIED_TIME, submissionTime)
          .where(APPLICATIONS.ID.eq(application.id))
          .execute()
    }

    with(APPLICATION_HISTORIES) {
      // Update the initial default "not submitted" history entry such that the application never
      // went through that status according to its history.
      dslContext
          .update(APPLICATION_HISTORIES)
          .set(APPLICATION_STATUS_ID, applicationStatus)
          .set(MODIFIED_TIME, submissionTime)
          .where(
              ID.eq(
                  DSL.select(DSL.min(ID))
                      .from(APPLICATION_HISTORIES)
                      .where(APPLICATION_ID.eq(application.id))
              )
          )
          .execute()
    }
  }

  private fun fetchCountryCode(dealName: String): String {
    val countryCodeAlpha3 = dealName.substring(0, 3)
    val countriesRow =
        countriesDao.fetchOneByCodeAlpha3(countryCodeAlpha3)
            ?: throw ImportError("Invalid country code prefix on deal name")
    val countryCode = countriesRow.code!!
    return countryCode
  }

  private fun getMandatory(valuesByName: Map<String, String>, key: String) =
      valuesByName[key] ?: throw ImportError("Missing required value in column $key")

  private fun getBigDecimal(valuesByName: Map<String, String>, key: String): BigDecimal? {
    return valuesByName[key]?.let { str ->
      try {
        BigDecimal(str)
      } catch (e: Exception) {
        throw ImportError("Value in column $key is not a number")
      }
    }
  }

  private fun getInt(valuesByName: Map<String, String>, key: String): Int? {
    return valuesByName[key]?.let { str ->
      try {
        str.toInt()
      } catch (e: Exception) {
        throw ImportError("Value in column $key is not an integer")
      }
    }
  }

  private class ImportError(message: String) : Exception(message)
}
