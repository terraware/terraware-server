package com.terraformation.backend.documentproducer.db.manifest

import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.embeddables.pojos.VariableManifestEntryId
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestEntriesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableNumbersRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionDefaultValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionRecommendationsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTableColumnsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTablesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTextsRow
import com.terraformation.backend.documentproducer.db.VariableManifestStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.model.DateVariable
import com.terraformation.backend.documentproducer.model.HierarchicalVariableType
import com.terraformation.backend.documentproducer.model.ImageVariable
import com.terraformation.backend.documentproducer.model.LinkVariable
import com.terraformation.backend.documentproducer.model.NewVariableManifestModel
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

data class ManifestImportResult(
    val newVersion: VariableManifestId?,
    val message: String,
    val results: List<String>,
    val errors: List<String>
)

@Named
class ManifestImporter(
    private val dslContext: DSLContext,
    private val messages: Messages,
    private val variableManifestStore: VariableManifestStore,
    private val variableStore: VariableStore,
) {
  private val log = perClassLogger()

  fun import(
      documentTemplateId: DocumentTemplateId,
      inputStream: InputStream,
  ): ManifestImportResult {
    val inputBytes = inputStream.readAllBytes()

    val validator = ManifestCsvValidator(messages)
    validator.validate(inputBytes)

    if (validator.errors.isNotEmpty()) {
      return ManifestImportResult(null, "Failure", emptyList(), validator.errors)
    }

    return try {
      ImportContext().importCsv(documentTemplateId, inputBytes)
    } catch (e: Exception) {
      ManifestImportResult(
          null,
          "Failure",
          listOf(),
          listOf("Error while attempting to import the new variable manifest - ${e.message}"),
      )
    }
  }

  private inner class ImportContext {
    lateinit var csvVariables: List<CsvVariable>
    lateinit var csvVariableByStableId: Map<String, CsvVariable>
    lateinit var variableManifestId: VariableManifestId

    /**
     * Map of full variable paths to CSV variables. This is the full "path" to the variable within
     * the hierarchy. For example "\tProject Details\tSummary\tProject Details", this allows us to
     * cache the values in memory without collisions due to non-uniqueness of the variable name.
     */
    lateinit var csvVariableByPath: Map<String, CsvVariable>

    /**
     * Map of full variable paths to CSV variables that are children of the path. The children are
     * in the same order they appear in the manifest.
     */
    lateinit var csvVariablesByParentPath: Map<String, List<CsvVariable>>

    val csvVariableNormalizer = CsvVariableNormalizer()

    var newVariablesImported = 0
    var variablesAttachedToNewManifest = 0

    val results = mutableListOf<String>()
    val errors = mutableListOf<String>()

    fun importCsv(
        documentTemplateId: DocumentTemplateId,
        inputBytes: ByteArray
    ): ManifestImportResult {
      try {
        csvVariables = csvVariableNormalizer.normalizeFromCsv(inputBytes)
        csvVariableByPath = csvVariables.associateBy { it.variablePath }
        csvVariableByStableId = csvVariables.associateBy { it.stableId }
        csvVariablesByParentPath =
            csvVariables.filter { it.parentPath != null }.groupBy { it.parentPath!! }

        dslContext.transaction { _ ->
          useExistingVariables(documentTemplateId)

          val newVariableManifest =
              variableManifestStore.create(NewVariableManifestModel(documentTemplateId))
          variableManifestId = newVariableManifest.id

          // Import tables first
          importCsvVariables(
              csvVariables = csvVariables, importVariableType = HierarchicalVariableType.Table)

          // Import all non tables, non sections
          importCsvVariables(
              csvVariables = csvVariables,
              ignoreVariableTypes =
                  listOf(HierarchicalVariableType.Table, HierarchicalVariableType.Section))

          // Import all sections
          importCsvVariables(
              csvVariables = csvVariables, importVariableType = HierarchicalVariableType.Section)

          if (errors.isNotEmpty()) {
            // Roll back the transaction so we don't end up with a partially-imported manifest.
            throw ImportAbortedDueToErrorsException()
          }
        }
      } catch (e: ImportAbortedDueToErrorsException) {
        return ManifestImportResult(null, "Failure", emptyList(), errors)
      } catch (e: Exception) {
        log.error("Exception thrown while importing", e)

        return ManifestImportResult(
            null,
            "Unexpected failure",
            emptyList(),
            listOf("Exception thrown while importing: $e") + errors)
      }

      return ManifestImportResult(variableManifestId, "Success", results, errors)
    }

    private fun useExistingVariables(documentTemplateId: DocumentTemplateId) {
      val existingManifestId =
          variableManifestStore.fetchVariableManifestByDocumentTemplate(documentTemplateId)?.id
              ?: return
      val existingVariables = variableStore.fetchManifestVariables(existingManifestId)

      // Child variables such as table columns and subsections are not in the top-level list, so we
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

    private fun importCsvVariables(
        csvVariables: List<CsvVariable>,
        importVariableType: HierarchicalVariableType? = null,
        ignoreVariableTypes: List<HierarchicalVariableType>? = null,
    ) {
      var filteredCsvVariables = csvVariables

      // Target specific variable type to import
      // When importing a specific variable type, we will also import its children. For example - a
      // "table" variable with several children of type "single text" will all be imported within
      // the same `importCsvVariables` run, in hierarchical order from parent down through all
      // descendents.
      if (importVariableType != null) {
        // Get all the variable names that are the correct data type
        val filteredCsvVariableNames =
            filteredCsvVariables
                .filter { it.dataType.value == importVariableType.value }
                .map { it.name }

        // Filter csv variables down to list of variables that are either the given type or children
        // of the given type
        filteredCsvVariables =
            filteredCsvVariables.filter { csvVariable ->
              variableNameOrParentWithinList(csvVariable, filteredCsvVariableNames)
            }
      }

      // Ignore specific variable types during import
      if (ignoreVariableTypes != null) {
        val ignoreDataTypes = ignoreVariableTypes.map { it.value }.toSet()
        filteredCsvVariables =
            filteredCsvVariables.filterNot { it.dataType.value in ignoreDataTypes }
      }

      filteredCsvVariables
          .filter { canImportVariable(it) }
          .forEach { csvVariable ->
            try {
              importVariable(csvVariable)
            } catch (e: Exception) {
              val errorMessage =
                  "Error while adding net new variable ${e.message} - position: ${csvVariable.position} - name: ${csvVariable.name}"
              if (errorMessage !in errors) {
                errors.add(errorMessage)
              }
            }
          }

      val notImported = filteredCsvVariables.filter { it.variableId == null }
      if (notImported.isNotEmpty()) {
        notImported.forEach { log.debug("Variable not imported: $it") }
        throw IllegalStateException("Some variables could not be imported")
      }
    }

    private fun variableNameOrParentWithinList(csvVariable: CsvVariable, names: List<String>) =
        names.intersect(setOf(csvVariable.name, csvVariable.parent)).isNotEmpty()

    // This is where we define the "extra" import instructions for specific variable types
    private fun importTypeSpecificVariable(csvVariable: CsvVariable) {
      when (csvVariable.dataType) {
        CsvVariableType.SingleSelect,
        CsvVariableType.MultiSelect -> importSelectVariable(csvVariable)
        CsvVariableType.Table -> importTableVariable(csvVariable)
        CsvVariableType.Section -> importSectionVariable(csvVariable)
        CsvVariableType.Number -> importNumberVariable(csvVariable)
        CsvVariableType.SingleLine,
        CsvVariableType.MultiLine -> importTextVariable(csvVariable)
        else -> Unit
      }

      if (csvVariableByPath[csvVariable.parentPath]?.dataType == CsvVariableType.Table) {
        importTableColumnVariable(csvVariable)
      }
    }

    private fun importSectionVariable(csvVariable: CsvVariable) {
      if (csvVariable.dataType != CsvVariableType.Section) {
        // This should be impossible since we are filtering the csv variables before we get here
        // But we should double-check
        throw IllegalStateException(
            "Attempting to import a section variable which is not of type 'section'")
      }

      val parentVariable = csvVariable.parentPath?.let { csvVariableByPath[it] }

      variableStore.importSectionVariable(
          VariableSectionsRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Section,
              parentVariableId = parentVariable?.variableId,
              parentVariableTypeId = if (parentVariable != null) VariableType.Section else null,
              renderHeading = !csvVariable.isNonNumberedSection))

      if (csvVariable.defaultSectionText != null) {
        val regex = Regex("(.*?)(?:\\{\\{([^}]+)}}|\$)", RegexOption.DOT_MATCHES_ALL)
        val textVariablePairs =
            regex.findAll(csvVariable.defaultSectionText).map { it.groupValues.drop(1) }
        var listPosition = 1

        val defaultValuesRows =
            textVariablePairs
                .flatMap { (textValue, variableName) ->
                  val textRow =
                      if (textValue.isNotEmpty()) {
                        VariableSectionDefaultValuesRow(
                            variableId = csvVariable.variableId,
                            variableTypeId = VariableType.Section,
                            variableManifestId = variableManifestId,
                            listPosition = listPosition++,
                            textValue = textValue,
                        )
                      } else {
                        null
                      }

                  val variableRow =
                      if (variableName.isNotEmpty()) {
                        val referencedCsvVariable =
                            csvVariableByStableId[variableName]
                                ?: csvVariableByPath["\t$variableName"]

                        if (referencedCsvVariable != null) {
                          VariableSectionDefaultValuesRow(
                              variableId = csvVariable.variableId,
                              variableTypeId = VariableType.Section,
                              variableManifestId = variableManifestId,
                              listPosition = listPosition++,
                              usedVariableId = referencedCsvVariable.variableId,
                              usedVariableTypeId = referencedCsvVariable.dataType.variableType,
                              usageTypeId = VariableUsageType.Injection,
                              displayStyleId = VariableInjectionDisplayStyle.Inline,
                          )
                        } else {
                          errors.add(
                              "Variable in default section text does not exist - position: " +
                                  "${csvVariable.position}, referenced variable: $variableName")
                          null
                        }
                      } else {
                        null
                      }

                  listOfNotNull(
                      textRow,
                      variableRow,
                  )
                }
                .toList()

        variableStore.importSectionDefaultValues(defaultValuesRows)
      }
    }

    private fun importRecommendedVariables(csvVariable: CsvVariable) {
      if (csvVariable.dataType == CsvVariableType.Section) {
        csvVariable.recommendedVariables.forEach { recommended ->
          val recommendedId = csvVariableByPath["\t$recommended"]?.variableId
          if (recommendedId != null) {
            variableStore.insertSectionRecommendation(
                VariableSectionRecommendationsRow(
                    recommendedVariableId = recommendedId,
                    sectionVariableId = csvVariable.variableId,
                    sectionVariableTypeId = VariableType.Section,
                    variableManifestId = variableManifestId,
                ))
          } else {
            errors.add(
                "Recommended variable does not exist - position: ${csvVariable.position}, " +
                    "recommended: $recommended")
          }
        }
      }
    }

    private fun importTableVariable(csvVariable: CsvVariable) {
      if (csvVariable.dataType != CsvVariableType.Table) {
        // This should be impossible since we are filtering the csv variables before we get here
        // But we should double-check
        throw IllegalStateException(
            "Attempting to import top level table variable which is not of type 'table'")
      }

      // Main table variable
      variableStore.importTableVariable(
          VariableTablesRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Table,
              tableStyleId = csvVariable.tableStyle))
    }

    private fun importTableColumnVariable(csvVariable: CsvVariable) {
      // Column Variable
      val tableVariableId: VariableId =
          csvVariableByPath[csvVariable.parentPath]?.variableId
              ?: throw IllegalStateException(
                  "Parent variable has not been imported - ${csvVariable.parent}")
      val zeroIndexedPosition =
          csvVariablesByParentPath[csvVariable.parentPath]?.indexOfFirst {
            it.variableId == csvVariable.variableId
          }
              ?: throw IllegalStateException(
                  "Variable ${csvVariable.variableId} not found in parent ${csvVariable.parentPath}")

      variableStore.importTableColumnVariable(
          VariableTableColumnsRow(
              variableId = csvVariable.variableId,
              tableVariableId = tableVariableId,
              tableVariableTypeId = VariableType.Table,
              position = zeroIndexedPosition + 1,
              isHeader = csvVariable.isHeader))
    }

    // Import select variable and select variable option rows
    private fun importSelectVariable(csvVariable: CsvVariable) {
      val selectsRow =
          VariableSelectsRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Select,
              isMultiple =
                  when (csvVariable.dataType) {
                    CsvVariableType.SingleSelect -> false
                    CsvVariableType.MultiSelect -> true
                    else ->
                        throw IllegalStateException(
                            "Attempting to import a Select Variable with non-select type variable data")
                  })
      // The options were hydrated before we saved the variable, so we need to add some data
      variableStore.importSelectVariable(
          selectsRow, csvVariable.options.map { it.copy(variableId = csvVariable.variableId) })
    }

    private fun importNumberVariable(csvVariable: CsvVariable) {
      variableStore.importNumberVariable(
          VariableNumbersRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Number,
              minValue = csvVariable.minValue,
              maxValue = csvVariable.maxValue,
              decimalPlaces = csvVariable.decimalPlaces))
    }

    private fun importTextVariable(csvVariable: CsvVariable) {
      variableStore.importTextVariable(
          VariableTextsRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Text,
              variableTextTypeId =
                  when (csvVariable.dataType) {
                    CsvVariableType.SingleLine -> VariableTextType.SingleLine
                    CsvVariableType.MultiLine -> VariableTextType.MultiLine
                    else ->
                        throw IllegalStateException(
                            "Attempt to import a non Text type CSV variable as a Text Variable")
                  }))
    }

    /**
     * Inserts a variable into the database. If the variable already exists and is being reused,
     * inserts a manifest entry pointing to the existing variable; otherwise inserts a new variable.
     *
     * Modifies [csvVariable] to set the variable ID and manifest entry key.
     */
    private fun importVariable(csvVariable: CsvVariable) {
      val variablesRow = csvVariable.mapToVariablesRow()
      if (variablesRow.variableTypeId == null) {
        // This theoretically should not be possible, but the field is optional on the VariablesRow
        // and this seems like a good validation as opposed to going back through
        // another csv type -> variable type `when` statement
        throw IllegalStateException(
            "Variable Row is missing a type ID - position: ${csvVariable.position}, name: ${csvVariable.name}")
      }

      val isNewVariable = variablesRow.id == null

      val variableId: VariableId =
          variablesRow.id
              ?: run {
                newVariablesImported++
                variableStore.importVariable(variablesRow)
              }

      val variableManifestEntriesRow =
          VariableManifestEntriesRow(
              variableId = variableId,
              variableManifestId = variableManifestId,
              position = csvVariable.position,
          )
      val variableManifestEntryKey: VariableManifestEntryId =
          variableManifestStore.addVariableToManifestEntries(variableManifestEntriesRow)
      variablesAttachedToNewManifest++

      csvVariable.variableId = variableId
      csvVariable.variableManifestEntryKey = variableManifestEntryKey

      if (isNewVariable) {
        importTypeSpecificVariable(csvVariable)
      }

      importRecommendedVariables(csvVariable)
    }

    private fun canImportVariable(csvVariable: CsvVariable): Boolean {
      // If the variable has a variable manifest entry key, it has already been imported.
      return csvVariable.variableManifestEntryKey == null
    }

    /**
     * Returns true if the list of select options in a CSV variable is the same as the options of an
     * existing variable.
     */
    private fun hasSameOptions(csvVariable: CsvVariable, variable: SelectVariable): Boolean {
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
     * variable. Manifest-specific settings on the columns (name, etc.) are ignored.
     */
    private fun hasSameColumns(csvVariable: CsvVariable, table: TableVariable): Boolean {
      val csvColumns = csvVariablesByParentPath[csvVariable.variablePath] ?: emptyList()

      return csvColumns.size == table.columns.size &&
          table.columns.zip(csvColumns).all { (tableColumn, csvColumn) ->
            tableColumn.isHeader == csvColumn.isHeader &&
                canReuseExistingVariable(csvColumn, tableColumn.variable)
          }
    }

    private fun hasSameSubsections(csvVariable: CsvVariable, section: SectionVariable): Boolean {
      val csvSubsections = csvVariablesByParentPath[csvVariable.variablePath] ?: emptyList()

      return csvSubsections.size == section.children.size &&
          section.children.zip(csvSubsections).all { (subsection, csvSubsection) ->
            canReuseExistingVariable(csvSubsection, subsection)
          }
    }

    /**
     * Returns true if a CSV variable has the same non-manifest-specific settings as an existing
     * variable, and thus the existing variable can be reused in the new manifest.
     *
     * Sections and tables are counted as reusable if they have the same children as the existing
     * variable and all the children are reusable. That is, non-reusability propagates from children
     * up to parents here. Propagation in the other direction happens elsewhere.
     */
    private fun canReuseExistingVariable(csvVariable: CsvVariable, variable: Variable): Boolean {
      return csvVariable.description == variable.description &&
          csvVariable.isList == variable.isList &&
          csvVariable.name == variable.name &&
          csvVariable.stableId == variable.stableId &&
          when (csvVariable.dataType) {
            CsvVariableType.Number ->
                variable is NumberVariable &&
                    csvVariable.minValue == variable.minValue &&
                    csvVariable.maxValue == variable.maxValue &&
                    (csvVariable.decimalPlaces ?: 0) == variable.decimalPlaces
            CsvVariableType.SingleLine ->
                variable is TextVariable && variable.textType == VariableTextType.SingleLine
            CsvVariableType.MultiLine ->
                variable is TextVariable && variable.textType == VariableTextType.MultiLine
            CsvVariableType.SingleSelect ->
                variable is SelectVariable &&
                    !variable.isMultiple &&
                    hasSameOptions(csvVariable, variable)
            CsvVariableType.MultiSelect ->
                variable is SelectVariable &&
                    variable.isMultiple &&
                    hasSameOptions(csvVariable, variable)
            CsvVariableType.Date -> variable is DateVariable
            CsvVariableType.Link -> variable is LinkVariable
            CsvVariableType.Image -> variable is ImageVariable
            CsvVariableType.Table ->
                variable is TableVariable &&
                    variable.tableStyle == csvVariable.tableStyle &&
                    hasSameColumns(csvVariable, variable)
            CsvVariableType.Section ->
                variable is SectionVariable &&
                    csvVariable.isNonNumberedSection == !variable.renderHeading &&
                    hasSameSubsections(csvVariable, variable)
          }
    }

    private fun markChildrenNonReusable(csvVariable: CsvVariable) {
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
