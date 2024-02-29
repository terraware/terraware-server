package com.terraformation.backend.accelerator.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
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
    private const val NUM_COLUMNS = COLUMN_ID + 1
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
        val name = values[COLUMN_NAME]

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
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .onConflict()
                .doUpdate()
                .set(NAME, name)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .execute()
          }
        }
      }
    }
  }
}
