package com.terraformation.backend.documentproducer

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.daos.VariableManifestsDao
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.UpgradeCannotChangeDocumentTemplateException
import com.terraformation.backend.documentproducer.db.VariableManifestNotFoundException
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ExistingSectionValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.NewSectionValue
import com.terraformation.backend.documentproducer.model.NewTableValue
import com.terraformation.backend.documentproducer.model.SectionValueFragment
import com.terraformation.backend.documentproducer.model.SectionValueText
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.documentproducer.model.UpdateValueOperation
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.log.perClassLogger

/**
 * Calculates the list of operations needed to upgrade a document to a new manifest.
 *
 * This is not a service; create a new instance of it for each upgrade.
 */
class DocumentUpgradeCalculator(
    private val documentId: DocumentId,
    private val newManifestId: VariableManifestId,
    private val documentStore: DocumentStore,
    private val variableManifestsDao: VariableManifestsDao,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  private val log = perClassLogger()

  private lateinit var projectId: ProjectId
  private lateinit var existingValues: Map<VariableId, List<ExistingValue>>
  private lateinit var documentsRow: DocumentsRow
  private lateinit var oldManifestId: VariableManifestId
  private lateinit var allVariables: Map<VariableId, Variable>
  private lateinit var newManifestVariables: Map<VariableId, Variable>
  /** Map of new variable ID to the list of older IDs it replaces. */
  private lateinit var previousVariableIds: Map<VariableId, List<VariableId>>
  /** Map of old variable ID to the new variable ID that replaces it. */
  private lateinit var replacementVariableIds: Map<VariableId, VariableId>

  fun calculateOperations(): List<ValueOperation> {
    init()

    return newManifestVariables.values.flatMap { variable -> variableOperations(variable) }
  }

  private fun init() {
    documentsRow = documentStore.fetchDocumentById(documentId)
    projectId = documentsRow.projectId!!

    oldManifestId = documentsRow.variableManifestId!!
    if (newManifestId.value < oldManifestId.value) {
      throw IllegalArgumentException(
          "Cannot downgrade from manifest $oldManifestId to older manifest $newManifestId"
      )
    }

    val oldManifest = variableManifestsDao.fetchOneById(oldManifestId)!!
    val newManifest =
        variableManifestsDao.fetchOneById(newManifestId)
            ?: throw VariableManifestNotFoundException(newManifestId)

    if (oldManifest.documentTemplateId != newManifest.documentTemplateId) {
      throw UpgradeCannotChangeDocumentTemplateException(
          oldManifest.documentTemplateId!!,
          oldManifestId,
          newManifest.documentTemplateId!!,
          newManifestId,
      )
    }

    newManifestVariables =
        variableStore
            .fetchManifestVariablesWithSubSectionsAndTableColumns(newManifestId)
            .associateBy { it.id }
    if (newManifestVariables.isEmpty()) {
      throw IllegalArgumentException("No variables defined in manifest $newManifestId")
    }

    // Used for finding SectionVariable fragment variables
    allVariables = variableStore.fetchAllNonSectionVariables().associateBy { it.id }

    previousVariableIds =
        (newManifestVariables.values + allVariables.values)
            .filter { it.replacesVariableId != null }
            .associate { it.id to variableStore.fetchReplacedVariables(it.id) }

    replacementVariableIds =
        previousVariableIds
            .flatMap { (newId, oldIds) -> oldIds.map { oldId -> oldId to newId } }
            .toMap()

    existingValues = variableValueStore.listValues(documentId).groupBy { it.variableId }
  }

  private fun variableOperations(variable: Variable): List<ValueOperation> {
    if (variable is SectionVariable) {
      // Values of section variables may need to be changed even if the section variables
      // themselves stayed the same, since they may have usages of out-of-date variables.
      return sectionOperations(variable)
    }

    // Nothing to do if this variable's value is already up-to-date or the variable isn't a
    // replacement for an earlier one.
    if (variable.id in existingValues || variable.replacesVariableId == null) {
      return emptyList()
    }

    val valuesOfReplacedVariable =
        previousVariableIds[variable.id]?.firstNotNullOfOrNull { existingValues[it] }

    return if (!valuesOfReplacedVariable.isNullOrEmpty()) {
      val oldVariable =
          variableStore.fetchOneVariable(valuesOfReplacedVariable.first().variableId, oldManifestId)

      if (variable is TableVariable) {
        if (oldVariable is TableVariable) {
          tableOperations(oldVariable, valuesOfReplacedVariable, variable)
        } else {
          // Replacing a non-table with a table is allowed, but unusual enough to be worth
          // flagging for someone to look at.
          log.warn("Variable ${variable.name} used to be ${oldVariable.type} but is now a table")
          emptyList()
        }
      } else {
        valuesOfReplacedVariable
            .mapNotNull { oldValue ->
              variable.convertValue(oldVariable, oldValue, null, variableStore::fetchOneVariable)
            }
            .map { AppendValueOperation(it) }
      }
    } else {
      emptyList()
    }
  }

  /**
   * Returns operations to update any section values that are references to variables that have been
   * replaced with new variables in the new manifest.
   */
  private fun sectionOperations(variable: SectionVariable): List<ValueOperation> {
    return if (variable.id in existingValues) {
      // The section wasn't replaced, so existing text values are fine, as are existing
      // references to variables that weren't replaced or removed; we just need to update
      // any references to old variables.
      val sectionValues = existingValues[variable.id]!!.filterIsInstance<ExistingSectionValue>()

      sectionValues.mapNotNull { sectionValue ->
        if (sectionValue.value is SectionValueVariable) {
          val sectionValueVariable = sectionValue.value
          val usedVariableId = sectionValueVariable.usedVariableId
          if (usedVariableId in replacementVariableIds) {
            UpdateValueOperation(
                ExistingSectionValue(
                    BaseVariableValueProperties(
                        sectionValue.id,
                        projectId,
                        sectionValue.listPosition,
                        variable.id,
                        sectionValue.citation,
                    ),
                    SectionValueVariable(
                        replacementVariableIds[usedVariableId]!!,
                        sectionValueVariable.usageType,
                        sectionValueVariable.displayStyle,
                    ),
                )
            )
          } else {
            null
          }
        } else {
          null
        }
      }
    } else {
      val sectionValues =
          previousVariableIds[variable.id]
              ?.firstNotNullOfOrNull { existingValues[it] }
              ?.filterIsInstance<ExistingSectionValue>()
      if (sectionValues.isNullOrEmpty()) {
        // This is a new section being added to a project document, we should see if a default value
        // exists
        return variableValueStore.calculateDefaultValues(projectId, newManifestId, variable.id)
      }

      sectionValues.flatMap { sectionValue ->
        val valueFragment: SectionValueFragment? =
            when (sectionValue.value) {
              is SectionValueText -> sectionValue.value
              is SectionValueVariable -> {
                val existingUsedVariableId = sectionValue.value.usedVariableId
                // This needs to come from all variables, since variables can
                // exist outside the context of a manifest
                val newUsedVariableId =
                    if (existingUsedVariableId in allVariables) {
                      existingUsedVariableId
                    } else {
                      replacementVariableIds[existingUsedVariableId]
                    }

                newUsedVariableId?.let {
                  SectionValueVariable(
                      it,
                      sectionValue.value.usageType,
                      sectionValue.value.displayStyle,
                  )
                }
              }
            }

        valueFragment?.let { validFragment ->
          listOf(
              AppendValueOperation(
                  NewSectionValue(
                      BaseVariableValueProperties(
                          null,
                          projectId,
                          0,
                          variable.id,
                          sectionValue.citation,
                      ),
                      validFragment,
                  )
              ),
              DeleteValueOperation(projectId, sectionValue.id),
          )
        } ?: emptyList()
      }
    }
  }

  private fun tableOperations(
      oldTable: TableVariable,
      oldRows: List<ExistingValue>,
      newTable: TableVariable,
  ): List<ValueOperation> {
    val oldColumns = oldTable.columns.map { it.variable }.associateBy { it.id }
    val deleteOldTableOperations =
        existingValues[oldTable.id]?.map { DeleteValueOperation(projectId, it.id) } ?: emptyList()

    val rowColOperations =
        oldRows.flatMap { oldRow ->
          val rowOperation =
              AppendValueOperation(
                  NewTableValue(
                      BaseVariableValueProperties(null, projectId, 0, newTable.id, oldRow.citation)
                  )
              )

          val deleteRowOperation = oldRow.rowValueId?.let { DeleteValueOperation(projectId, it) }

          val columnOperations =
              newTable.columns
                  .map { it.variable }
                  .flatMap { newColumn ->
                    if (
                        newColumn.replacesVariableId != null &&
                            newColumn.replacesVariableId in oldColumns
                    ) {
                      val oldColumn = oldColumns[newColumn.replacesVariableId]!!

                      val appendOps =
                          existingValues[newColumn.replacesVariableId]
                              ?.filter { it.rowValueId == oldRow.id }
                              ?.sortedBy { it.listPosition }
                              ?.mapNotNull { oldValue ->
                                newColumn.convertValue(
                                    oldColumn,
                                    oldValue,
                                    null,
                                    variableStore::fetchOneVariable,
                                )
                              }
                              ?.map { AppendValueOperation(it) } ?: emptyList()
                      val deleteOps =
                          existingValues[newColumn.replacesVariableId]
                              ?.filter { it.rowValueId == oldRow.id }
                              ?.map { DeleteValueOperation(projectId, it.id) } ?: emptyList()

                      appendOps + deleteOps
                    } else {
                      emptyList<ValueOperation>()
                    }
                  }

          val operations =
              listOf(rowOperation) +
                  (if (deleteRowOperation != null) listOf(deleteRowOperation) else emptyList()) +
                  columnOperations

          operations
        }

    return deleteOldTableOperations + rowColOperations
  }
}
