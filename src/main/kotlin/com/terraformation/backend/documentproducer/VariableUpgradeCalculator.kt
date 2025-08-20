package com.terraformation.backend.documentproducer

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.variable.VariableImporter
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.NewTableValue
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.log.perClassLogger

/**
 * Calculates the list of operations needed to upgrade variables from the all-variables list to the
 * latest versions.
 *
 * This is not a service; create a new instance of it for each upgrade.
 */
class VariableUpgradeCalculator(
    /**
     * Which old variables were replaced by which new variables in the new version of the
     * all-variables list. This typically comes from the return value of [VariableImporter.import].
     */
    private val replacements: Map<VariableId, VariableId>,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  private val log = perClassLogger()

  /** Map of new variable ID to the older ID it replaces. The inverse of [replacements]. */
  private val oldVariableIds: Map<VariableId, VariableId> =
      replacements.entries.associate { it.value to it.key }

  private lateinit var valuesOfOldVariables: Map<VariableId, List<ExistingValue>>

  fun calculateOperations(): List<ValueOperation> {
    val newVariableIds = replacements.values
    val newVariables = newVariableIds.map { variableStore.fetchOneVariable(it) }
    val tableColumnVariableIds =
        newVariables
            .filterIsInstance<TableVariable>()
            .flatMap { variable -> variable.columns.map { it.variable.id } }
            .toSet()

    val projectsWithValuesForNewVariables: Map<VariableId, Set<ProjectId>> =
        variableValueStore.fetchProjectsWithValues(newVariableIds)

    valuesOfOldVariables =
        variableValueStore
            .listValues(replacements.keys)
            .filter { oldValue ->
              // If the new variable already has a value for a project, don't overwrite it by
              // upgrading the old values.
              //
              // There won't be values yet in the common case of upgrading as part of importing a
              // new all-variables list since the new variables will have just been created, but
              // there might be if this is a manually-triggered upgrade to backfill values for
              // variables that were replaced before this class existed.

              val projects = projectsWithValuesForNewVariables[replacements[oldValue.variableId]]
              projects == null || oldValue.projectId !in projects
            }
            .groupBy { it.variableId }

    return newVariables
        .filterNot { variable ->
          // Values of table columns are upgraded as part of the upgrade of the table.
          variable.id in tableColumnVariableIds
        }
        .flatMap { variableOperations(it) }
  }

  private fun variableOperations(newVariable: Variable): List<ValueOperation> {
    val valuesOfOldVariable =
        oldVariableIds[newVariable.id]?.let { valuesOfOldVariables[it] } ?: return emptyList()

    val oldVariable = variableStore.fetchOneVariable(valuesOfOldVariable.first().variableId)

    return if (newVariable is TableVariable) {
      if (oldVariable is TableVariable) {
        tableOperations(oldVariable, valuesOfOldVariable, newVariable)
      } else {
        // Replacing a non-table with a table is allowed, but unusual enough to be worth
        // flagging for someone to look at.
        log.warn("Variable ${newVariable.name} used to be ${oldVariable.type} but is now a table")
        emptyList()
      }
    } else {
      valuesOfOldVariable
          .mapNotNull { oldValue ->
            newVariable.convertValue(oldVariable, oldValue, null, variableStore::fetchOneVariable)
          }
          .map { AppendValueOperation(it) }
    }
  }

  private fun tableOperations(
      oldTable: TableVariable,
      oldRows: List<ExistingValue>,
      newTable: TableVariable,
  ): List<ValueOperation> {
    val oldColumnsByStableId = oldTable.columns.associate { it.variable.stableId to it.variable }

    // old column ID -> old row value ID -> list of values in the old cell
    val valuesOfOldCells: Map<VariableId, Map<VariableValueId, List<ExistingValue>>> =
        oldTable.columns
            .mapNotNull { oldColumn ->
              valuesOfOldVariables[oldColumn.variable.id]?.let { oldColumnValues ->
                val valuesByOldRowValueId =
                    oldColumnValues
                        .filter { it.rowValueId != null }
                        .sortedBy { it.listPosition }
                        .groupBy { it.rowValueId!! }

                oldColumn.variable.id to valuesByOldRowValueId
              }
            }
            .toMap()

    return oldRows.flatMap { oldRow ->
      val rowOperation =
          AppendValueOperation(
              NewTableValue(
                  BaseVariableValueProperties(
                      null,
                      oldRow.projectId,
                      0,
                      newTable.id,
                      oldRow.citation,
                  )
              )
          )

      val columnOperations =
          newTable.columns
              .map { it.variable }
              .flatMap { newColumn ->
                val oldColumn = oldColumnsByStableId[newColumn.stableId]

                oldColumn
                    ?.let { valuesOfOldCells[oldColumn.id]?.get(oldRow.id) }
                    ?.mapNotNull { oldValue ->
                      newColumn.convertValue(
                          oldColumn,
                          oldValue,
                          null,
                          variableStore::fetchOneVariable,
                      )
                    }
                    ?.map { AppendValueOperation(it) } ?: emptyList()
              }

      listOf(rowOperation) + columnOperations
    }
  }
}
