package com.terraformation.backend.documentproducer.db.variable

import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_VARIABLES
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.pojos.VariableNumbersRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTableColumnsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTablesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTextsRow
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.model.DateVariable
import com.terraformation.backend.documentproducer.model.EmailVariable
import com.terraformation.backend.documentproducer.model.HierarchicalVariableType
import com.terraformation.backend.documentproducer.model.ImageVariable
import com.terraformation.backend.documentproducer.model.LinkVariable
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.documentproducer.model.TextVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import org.jooq.DSLContext

data class VariableImportResult(
    val errors: List<String>,
    /**
     * For variables that were replaced with new versions, a map of the previous variable ID to the
     * replacement ID.
     */
    val replacements: Map<VariableId, VariableId> = emptyMap(),
)

@Named
class VariableImporter(
    private val deliverableStore: DeliverableStore,
    private val dslContext: DSLContext,
    private val messages: Messages,
    private val variableStore: VariableStore,
) {
  private val log = perClassLogger()

  fun import(
      inputStream: InputStream,
  ): VariableImportResult {
    val inputBytes = inputStream.readAllBytes()

    val validator = VariableCsvValidator(messages, deliverableStore)
    validator.validate(inputBytes)

    if (validator.errors.isNotEmpty()) {
      return VariableImportResult(validator.errors)
    }

    return try {
      ImportContext().importCsv(inputBytes)
    } catch (e: Exception) {
      VariableImportResult(
          listOf("Error while attempting to import the all variables CSV - ${e.message}"),
      )
    }
  }

  private inner class ImportContext {
    lateinit var csvVariables: List<AllVariableCsvVariable>
    lateinit var csvVariableByStableId: Map<StableId, AllVariableCsvVariable>

    /**
     * Map of full variable paths to CSV variables. This is the full "path" to the variable within
     * the hierarchy. For example "\tProject Details\tSummary\tProject Details", this allows us to
     * cache the values in memory without collisions due to non-uniqueness of the variable name.
     */
    lateinit var csvVariableByPath: Map<String, AllVariableCsvVariable>

    /**
     * Map of full variable paths to CSV variables that are children of the path. The children are
     * in the same order they appear in the manifest.
     */
    lateinit var csvVariablesByParentPath: Map<String, List<AllVariableCsvVariable>>

    val csvVariableNormalizer = CsvVariableNormalizer()

    var newVariablesImported = 0

    val errors = mutableListOf<String>()
    val replacements = mutableMapOf<VariableId, VariableId>()

    fun importCsv(inputBytes: ByteArray): VariableImportResult {
      try {
        csvVariables = csvVariableNormalizer.normalizeFromCsv(inputBytes)
        csvVariableByPath = csvVariables.associateBy { it.variablePath }
        csvVariableByStableId = csvVariables.associateBy { it.stableId }
        csvVariablesByParentPath =
            csvVariables.filter { it.parentPath != null }.groupBy { it.parentPath!! }

        dslContext.transaction { _ ->
          useExistingVariables()

          // Import tables first
          importAllVariableCsvVariables(
              csvVariables = csvVariables,
              importVariableType = HierarchicalVariableType.Table,
          )

          // Import all non tables, non sections
          importAllVariableCsvVariables(
              csvVariables = csvVariables,
              ignoreVariableTypes =
                  listOf(HierarchicalVariableType.Table, HierarchicalVariableType.Section),
          )

          if (errors.isNotEmpty()) {
            // Roll back the transaction so we don't end up with a partially-imported manifest.
            throw ImportAbortedDueToErrorsException()
          }
        }
      } catch (e: ImportAbortedDueToErrorsException) {
        return VariableImportResult(errors)
      } catch (e: Exception) {
        log.error("Exception thrown while importing", e)

        return VariableImportResult(listOf("Exception thrown while importing: $e") + errors)
      }

      return VariableImportResult(errors, replacements)
    }

    private fun useExistingVariables() {
      val existingVariables = variableStore.fetchAllNonSectionVariables()

      // Child variables such as table columns are not in the top-level list, so we
      // need to recursively process them.
      fun processVariables(variables: List<Variable>) {
        variables.forEach { variable ->
          val csvVariable = csvVariableByStableId[variable.stableId]
          if (csvVariable != null) {
            if (canReuseExistingVariable(csvVariable, variable)) {
              csvVariable.variableId = variable.id
            } else {
              csvVariable.replacesVariableId = variable.id
            }
          }

          if (variable is TableVariable) {
            processVariables(variable.columns.map { it.variable })
          } else if (variable is SectionVariable) {
            processVariables(variable.children)
          }
        }
      }

      processVariables(existingVariables)

      // At this point, tables and top-level sections are marked as non-reusable if any of their
      // children are non-reusable. But some of the children might still be marked as reusable, so
      // we need to propagate the non-reusability back down the hierarchy.
      existingVariables
          .mapNotNull { csvVariableByStableId[it.stableId] }
          .filter { it.variableId == null }
          .forEach { markChildrenNonReusable(it) }
    }

    private fun importAllVariableCsvVariables(
        csvVariables: List<AllVariableCsvVariable>,
        importVariableType: HierarchicalVariableType? = null,
        ignoreVariableTypes: List<HierarchicalVariableType>? = null,
    ) {
      var filteredAllVariableCsvVariables = csvVariables

      // Target specific variable type to import
      // When importing a specific variable type, we will also import its children. For example - a
      // "table" variable with several children of type "single text" will all be imported within
      // the same `importAllVariableCsvVariables` run, in hierarchical order from parent down
      // through all
      // descendents.
      if (importVariableType != null) {
        // Get all the variable names that are the correct data type
        val filteredAllVariableCsvVariableNames =
            filteredAllVariableCsvVariables
                .filter { it.dataType.value == importVariableType.value }
                .map { it.name }

        // Filter csv variables down to list of variables that are either the given type or children
        // of the given type
        filteredAllVariableCsvVariables =
            filteredAllVariableCsvVariables.filter { csvVariable ->
              variableNameOrParentWithinList(csvVariable, filteredAllVariableCsvVariableNames)
            }
      }

      // Ignore specific variable types during import
      if (ignoreVariableTypes != null) {
        val ignoreDataTypes = ignoreVariableTypes.map { it.value }.toSet()
        filteredAllVariableCsvVariables =
            filteredAllVariableCsvVariables.filterNot { it.dataType.value in ignoreDataTypes }
      }

      filteredAllVariableCsvVariables.forEach { csvVariable ->
        try {
          importVariable(csvVariable)
        } catch (e: Exception) {
          val errorMessage =
              "Error while adding net new variable at position ${csvVariable.position} with name ${csvVariable.name} - ${e.message} "
          if (errorMessage !in errors) {
            errors.add(errorMessage)
          }
        }
      }

      val notImported = filteredAllVariableCsvVariables.filter { it.variableId == null }
      if (notImported.isNotEmpty()) {
        notImported.forEach { log.debug("Variable not imported: $it") }
        throw IllegalStateException("Some variables could not be imported")
      }
    }

    private fun variableNameOrParentWithinList(
        csvVariable: AllVariableCsvVariable,
        names: List<String>,
    ) = names.intersect(setOf(csvVariable.name, csvVariable.parent)).isNotEmpty()

    // This is where we define the "extra" import instructions for specific variable types
    private fun importTypeSpecificVariable(csvVariable: AllVariableCsvVariable) {
      when (csvVariable.dataType) {
        AllVariableCsvVariableType.SingleSelect,
        AllVariableCsvVariableType.MultiSelect -> importSelectVariable(csvVariable)
        AllVariableCsvVariableType.Table -> importTableVariable(csvVariable)
        AllVariableCsvVariableType.Number -> importNumberVariable(csvVariable)
        AllVariableCsvVariableType.SingleLine,
        AllVariableCsvVariableType.MultiLine -> importTextVariable(csvVariable)
        else -> {}
      }

      if (csvVariableByPath[csvVariable.parentPath]?.dataType == AllVariableCsvVariableType.Table) {
        importTableColumnVariable(csvVariable)
      }
    }

    private fun importTableVariable(csvVariable: AllVariableCsvVariable) {
      if (csvVariable.dataType != AllVariableCsvVariableType.Table) {
        // This should be impossible since we are filtering the csv variables before we get here
        // But we should double-check
        throw IllegalStateException(
            "Attempting to import top level table variable which is not of type 'table'"
        )
      }

      // Main table variable
      variableStore.importTableVariable(
          VariableTablesRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Table,
              tableStyleId = csvVariable.tableStyle,
          )
      )
    }

    private fun importTableColumnVariable(csvVariable: AllVariableCsvVariable) {
      // Column Variable
      val tableVariableId: VariableId =
          csvVariableByPath[csvVariable.parentPath]?.variableId
              ?: throw IllegalStateException(
                  "Parent variable has not been imported - ${csvVariable.parent}"
              )
      val zeroIndexedPosition =
          csvVariablesByParentPath[csvVariable.parentPath]?.indexOfFirst {
            it.variableId == csvVariable.variableId
          }
              ?: throw IllegalStateException(
                  "Variable ${csvVariable.variableId} not found in parent ${csvVariable.parentPath}"
              )

      variableStore.importTableColumnVariable(
          VariableTableColumnsRow(
              variableId = csvVariable.variableId,
              tableVariableId = tableVariableId,
              tableVariableTypeId = VariableType.Table,
              position = zeroIndexedPosition + 1,
              isHeader = csvVariable.isHeader,
          )
      )
    }

    // Import select variable and select variable option rows
    private fun importSelectVariable(csvVariable: AllVariableCsvVariable) {
      val selectsRow =
          VariableSelectsRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Select,
              isMultiple =
                  when (csvVariable.dataType) {
                    AllVariableCsvVariableType.SingleSelect -> false
                    AllVariableCsvVariableType.MultiSelect -> true
                    else ->
                        throw IllegalStateException(
                            "Attempting to import a Select Variable with non-select type variable data"
                        )
                  },
          )
      // The options were hydrated before we saved the variable, so we need to add some data
      variableStore.importSelectVariable(
          selectsRow,
          csvVariable.options.map { it.copy(variableId = csvVariable.variableId) },
      )
    }

    private fun importNumberVariable(csvVariable: AllVariableCsvVariable) {
      variableStore.importNumberVariable(
          VariableNumbersRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Number,
              minValue = csvVariable.minValue,
              maxValue = csvVariable.maxValue,
              decimalPlaces = csvVariable.decimalPlaces,
          )
      )
    }

    private fun importTextVariable(csvVariable: AllVariableCsvVariable) {
      variableStore.importTextVariable(
          VariableTextsRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Text,
              variableTextTypeId =
                  when (csvVariable.dataType) {
                    AllVariableCsvVariableType.SingleLine -> VariableTextType.SingleLine
                    AllVariableCsvVariableType.MultiLine -> VariableTextType.MultiLine
                    else ->
                        throw IllegalStateException(
                            "Attempt to import a non Text type CSV variable as a Text Variable"
                        )
                  },
          )
      )
    }

    /**
     * Inserts a variable into the database. If the variable already exists and is being reused,
     * inserts a manifest entry pointing to the existing variable; otherwise inserts a new variable.
     *
     * Modifies [csvVariable] to set the variable ID and manifest entry key.
     */
    private fun importVariable(csvVariable: AllVariableCsvVariable) {
      val variablesRow = csvVariable.mapToVariablesRow()
      if (variablesRow.variableTypeId == null) {
        // This theoretically should not be possible, but the field is optional on the VariablesRow
        // and this seems like a good validation as opposed to going back through
        // another csv type -> variable type `when` statement
        throw IllegalStateException(
            "Variable Row is missing a type ID - position: ${csvVariable.position}, name: ${csvVariable.name}"
        )
      }

      val isNewVariable = variablesRow.id == null

      val variableId: VariableId =
          variablesRow.id
              ?: run {
                newVariablesImported++
                variableStore.importVariable(variablesRow)
              }

      csvVariable.variableId = variableId

      // If this variable is a new version of an existing one, track the replacement.
      csvVariable.replacesVariableId?.let { replacements[it] = variableId }

      if (isNewVariable) {
        importTypeSpecificVariable(csvVariable)

        // If this variable replaces an existing one and the existing one is associated with
        // deliverables, the deliverables should be updated to use this new variable instead.
        if (csvVariable.replacesVariableId != null) {
          with(DELIVERABLE_VARIABLES) {
            dslContext
                .update(DELIVERABLE_VARIABLES)
                .set(VARIABLE_ID, variableId)
                .where(VARIABLE_ID.eq(csvVariable.replacesVariableId))
                .execute()
          }
        }
      }
    }

    /**
     * Returns true if the list of select options in a CSV variable is the same as the options of an
     * existing variable.
     */
    private fun hasSameOptions(
        csvVariable: AllVariableCsvVariable,
        variable: SelectVariable,
    ): Boolean {
      val variableOptions =
          variable.options.mapIndexed { index, selectOption ->
            VariableSelectOptionsRow(
                name = selectOption.name,
                position = index + 1,
                renderedText = selectOption.renderedText,
                variableTypeId = VariableType.Select,
            )
          }

      return variableOptions == csvVariable.options
    }

    /**
     * Returns true if a CSV variable representing a table has the same columns as an existing table
     * variable.
     */
    private fun hasSameColumns(csvVariable: AllVariableCsvVariable, table: TableVariable): Boolean {
      val csvColumns = csvVariablesByParentPath[csvVariable.variablePath] ?: emptyList()

      return csvColumns.size == table.columns.size &&
          table.columns.zip(csvColumns).all { (tableColumn, csvColumn) ->
            tableColumn.isHeader == csvColumn.isHeader &&
                canReuseExistingVariable(csvColumn, tableColumn.variable)
          }
    }

    /**
     * Returns true if a CSV variable has the same settings as an existing variable, and thus the
     * existing variable is still usable.
     *
     * Tables are counted as reusable if they have the same children as the existing variable and
     * all the children are reusable. That is, non-reusability propagates from children up to
     * parents here. Propagation in the other direction happens elsewhere.
     */
    private fun canReuseExistingVariable(
        csvVariable: AllVariableCsvVariable,
        variable: Variable,
    ): Boolean {
      return csvVariable.description == variable.description &&
          csvVariable.dependencyCondition == variable.dependencyCondition &&
          csvVariable.dependencyValue == variable.dependencyValue &&
          csvVariable.dependencyVariableStableId == variable.dependencyVariableStableId &&
          csvVariable.internalOnly == variable.internalOnly &&
          csvVariable.isList == variable.isList &&
          csvVariable.isRequired == variable.isRequired &&
          csvVariable.name == variable.name &&
          csvVariable.stableId == variable.stableId &&
          when (csvVariable.dataType) {
            AllVariableCsvVariableType.Number ->
                variable is NumberVariable &&
                    csvVariable.minValue == variable.minValue &&
                    csvVariable.maxValue == variable.maxValue &&
                    csvVariable.decimalPlaces == variable.decimalPlaces
            AllVariableCsvVariableType.SingleLine ->
                variable is TextVariable && variable.textType == VariableTextType.SingleLine
            AllVariableCsvVariableType.MultiLine ->
                variable is TextVariable && variable.textType == VariableTextType.MultiLine
            AllVariableCsvVariableType.SingleSelect ->
                variable is SelectVariable &&
                    !variable.isMultiple &&
                    hasSameOptions(csvVariable, variable)
            AllVariableCsvVariableType.MultiSelect ->
                variable is SelectVariable &&
                    variable.isMultiple &&
                    hasSameOptions(csvVariable, variable)
            AllVariableCsvVariableType.Date -> variable is DateVariable
            AllVariableCsvVariableType.Email -> variable is EmailVariable
            AllVariableCsvVariableType.Link -> variable is LinkVariable
            AllVariableCsvVariableType.Image -> variable is ImageVariable
            AllVariableCsvVariableType.Table ->
                variable is TableVariable &&
                    variable.tableStyle == csvVariable.tableStyle &&
                    hasSameColumns(csvVariable, variable)
          }
    }

    private fun markChildrenNonReusable(csvVariable: AllVariableCsvVariable) {
      csvVariablesByParentPath[csvVariable.variablePath]?.forEach { child ->
        if (child.variableId != null) {
          child.replacesVariableId = child.variableId
          child.variableId = null
        }

        markChildrenNonReusable(child)
      }
    }
  }

  private class ImportAbortedDueToErrorsException : RuntimeException()
}
