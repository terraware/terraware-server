package com.terraformation.backend.accelerator.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.importer.processCsvFile
import jakarta.inject.Named
import java.io.InputStream
import java.time.InstantSource
import org.jooq.DSLContext

/** Imports the list of modules from a CSV file. */
@Named
class ModulesImporter(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  companion object {
    private const val COLUMN_NAME = 0
    private const val COLUMN_ID = COLUMN_NAME + 1
    private const val COLUMN_PHASE = COLUMN_ID + 1
    private const val COLUMN_OVERVIEW = COLUMN_PHASE + 1
    private const val COLUMN_PREPARATION_MATERIALS = COLUMN_OVERVIEW + 1
    private const val COLUMN_ADDITIONAL_RESOURCES = COLUMN_PREPARATION_MATERIALS + 1
    private const val COLUMN_LIVE_SESSION_INFO = COLUMN_ADDITIONAL_RESOURCES + 1
    private const val COLUMN_ONE_ON_ONE_INFO = COLUMN_LIVE_SESSION_INFO + 1
    private const val COLUMN_WORKSHOP_INFO = COLUMN_ONE_ON_ONE_INFO + 1
    private const val NUM_COLUMNS = COLUMN_WORKSHOP_INFO + 1
  }

  fun importModules(inputStream: InputStream) {
    requirePermissions { manageModules() }

    val userId = currentUser().userId
    val now = clock.instant()

    dslContext.transaction { _ ->
      processCsvFile(inputStream) { values, _, addError ->
        if (values.size != NUM_COLUMNS) {
          addError("Expected $NUM_COLUMNS columns but found ${values.size}")
          return@processCsvFile
        }

        val moduleId = values[COLUMN_ID]?.toLongOrNull()?.let { ModuleId(it) }
        val phase = values[COLUMN_PHASE]?.toIntOrNull()?.let { CohortPhase.forId(it) }
        val name = values[COLUMN_NAME]
        val overview = values[COLUMN_OVERVIEW]
        val preparationMaterials = values[COLUMN_PREPARATION_MATERIALS]
        val additionalResources = values[COLUMN_ADDITIONAL_RESOURCES]
        val liveSessionInfo = values[COLUMN_LIVE_SESSION_INFO]
        val oneOnOneInfo = values[COLUMN_ONE_ON_ONE_INFO]
        val workshopInfo = values[COLUMN_WORKSHOP_INFO]

        if (moduleId == null) {
          addError("Missing or invalid module ID")
        }
        if (name == null) {
          addError("Missing module name")
        }

        if (moduleId != null && name != null) {
          with(MODULES) {
            dslContext
                .insertInto(MODULES)
                .set(ID, moduleId)
                .set(NAME, name)
                .set(PHASE_ID, phase)
                .set(OVERVIEW, overview)
                .set(PREPARATION_MATERIALS, preparationMaterials)
                .set(ADDITIONAL_RESOURCES, additionalResources)
                .set(LIVE_SESSION_DESCRIPTION, liveSessionInfo)
                .set(ONE_ON_ONE_SESSION_DESCRIPTION, oneOnOneInfo)
                .set(WORKSHOP_DESCRIPTION, workshopInfo)
                .set(PHASE_ID, CohortPhase.Phase1FeasibilityStudy)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .onConflict()
                .doUpdate()
                .set(NAME, name)
                .set(PHASE_ID, phase)
                .set(OVERVIEW, overview)
                .set(PREPARATION_MATERIALS, preparationMaterials)
                .set(ADDITIONAL_RESOURCES, additionalResources)
                .set(LIVE_SESSION_DESCRIPTION, liveSessionInfo)
                .set(ONE_ON_ONE_SESSION_DESCRIPTION, oneOnOneInfo)
                .set(WORKSHOP_DESCRIPTION, workshopInfo)
                .set(PHASE_ID, CohortPhase.Phase1FeasibilityStudy)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .execute()
          }
        }
      }
    }
  }
}
