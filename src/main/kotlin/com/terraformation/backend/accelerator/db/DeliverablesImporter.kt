package com.terraformation.backend.accelerator.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_DOCUMENTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.importer.CsvImportFailedException
import com.terraformation.backend.importer.processCsvFile
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.time.InstantSource
import org.jooq.DSLContext

/** Imports the list of deliverables from a CSV file. */
@Named
class DeliverablesImporter(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  companion object {
    private const val COLUMN_NAME = 0
    private const val COLUMN_ID = COLUMN_NAME + 1
    private const val COLUMN_DESCRIPTION = COLUMN_ID + 1
    private const val COLUMN_TEMPLATE = COLUMN_DESCRIPTION + 1
    private const val COLUMN_MODULE_ID = COLUMN_TEMPLATE + 1
    private const val COLUMN_CATEGORY = COLUMN_MODULE_ID + 1
    private const val COLUMN_SENSITIVE = COLUMN_CATEGORY + 1
    private const val COLUMN_REQUIRED = COLUMN_SENSITIVE + 1
    private const val NUM_COLUMNS = COLUMN_REQUIRED + 1

    /** Values we treat as true in boolean columns. */
    private val trueValues = setOf("y", "yes", "true", "t")

    private val deliverableCategoriesByName =
        DeliverableCategory.entries.associateBy { it.jsonValue.lowercase() }
    private val validDeliverableCategories =
        DeliverableCategory.entries.map { it.jsonValue }.sorted()
  }

  fun importDeliverables(inputStream: InputStream) {
    requirePermissions { manageDeliverables() }

    val userId = currentUser().userId
    val now = clock.instant()

    val validModuleIds =
        dslContext.select(MODULES.ID).from(MODULES).fetchSet(MODULES.ID.asNonNullable())

    dslContext.transaction { _ ->
      // Flip the signs on the positions of all the existing deliverables so they don't conflict
      // with the new positions if we, e.g., swap the order of two deliverables. Needed because
      // positions are required to be unique for a given deliverable and we'd hit a unique
      // constraint violation on whichever row we tried to update first if we were swapping
      // positions.
      //
      // This is also used to detect deliverables that were deleted from the spreadsheet.
      dslContext
          .update(DELIVERABLES)
          .set(DELIVERABLES.POSITION, DELIVERABLES.POSITION.neg())
          .execute()

      processCsvFile(inputStream) { values, rowNumber, addError ->
        if (values.size != NUM_COLUMNS) {
          addError("Expected $NUM_COLUMNS columns but found ${values.size}")
          return@processCsvFile
        }

        val deliverableId = values[COLUMN_ID]?.toLongOrNull()?.let { DeliverableId(it) }
        val moduleId = values[COLUMN_MODULE_ID]?.toLongOrNull()?.let { ModuleId(it) }
        val category = values[COLUMN_CATEGORY]?.lowercase()?.let { deliverableCategoriesByName[it] }
        val name = values[COLUMN_NAME]
        val isRequired = values[COLUMN_REQUIRED]?.lowercase() in trueValues
        val isSensitive = values[COLUMN_SENSITIVE]?.lowercase() in trueValues
        val templateUrl =
            values[COLUMN_TEMPLATE]?.let {
              try {
                URI.create(it)
              } catch (e: Exception) {
                addError("Invalid template URL $it")
                null
              }
            }

        if (deliverableId == null || deliverableId.value <= 0) {
          if (values[COLUMN_ID] != null) {
            addError("Deliverable ID \"${values[COLUMN_ID]}\" invalid")
          } else {
            addError("Missing deliverable ID")
          }
        }
        if (moduleId == null || moduleId.value <= 0) {
          if (values[COLUMN_MODULE_ID] != null) {
            addError("Module ID \"${values[COLUMN_MODULE_ID]}\" invalid")
          } else {
            addError("Missing module ID.")
          }
        } else if (moduleId !in validModuleIds) {
          addError(
              "Unknown module ID $moduleId; if this is a new module, upload the revised module " +
                  "list first and try again")
        }
        if (category == null) {
          if (values[COLUMN_CATEGORY] != null) {
            addError(
                "Category \"${values[COLUMN_CATEGORY]}\" invalid. Valid categories: $validDeliverableCategories")
          } else {
            addError("Missing category.")
          }
        }
        if (name == null) {
          addError("Missing deliverable name")
        }

        if (deliverableId != null &&
            moduleId != null &&
            moduleId in validModuleIds &&
            category != null &&
            name != null) {
          with(DELIVERABLES) {
            dslContext
                .insertInto(DELIVERABLES)
                .set(ID, deliverableId)
                .set(DELIVERABLE_CATEGORY_ID, category)
                .set(DELIVERABLE_TYPE_ID, DeliverableType.Document)
                .set(MODULE_ID, moduleId)
                .set(POSITION, rowNumber)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(NAME, name)
                .set(IS_SENSITIVE, isSensitive)
                .set(IS_REQUIRED, isRequired)
                .set(DESCRIPTION_HTML, values[COLUMN_DESCRIPTION])
                .onConflict()
                .doUpdate()
                .set(DELIVERABLE_CATEGORY_ID, category)
                .set(MODULE_ID, moduleId)
                .set(POSITION, rowNumber)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(NAME, name)
                .set(IS_SENSITIVE, isSensitive)
                .set(IS_REQUIRED, isRequired)
                .set(DESCRIPTION_HTML, values[COLUMN_DESCRIPTION])
                .execute()
          }

          with(DELIVERABLE_DOCUMENTS) {
            dslContext
                .insertInto(DELIVERABLE_DOCUMENTS)
                .set(DELIVERABLE_ID, deliverableId)
                .set(DELIVERABLE_TYPE_ID, DeliverableType.Document)
                .set(TEMPLATE_URL, templateUrl)
                .onConflict()
                .doUpdate()
                .set(TEMPLATE_URL, templateUrl)
                .execute()
          }
        }
      }

      val leftOverNegativePositions =
          dslContext
              .select(DELIVERABLES.ID)
              .from(DELIVERABLES)
              .where(DELIVERABLES.POSITION.lt(0))
              .fetch(DELIVERABLES.ID)

      if (leftOverNegativePositions.isNotEmpty()) {
        throw CsvImportFailedException(
            emptyList(),
            "Deleting deliverables isn't supported yet. Missing IDs: $leftOverNegativePositions")
      }
    }
  }
}
