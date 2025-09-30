package com.terraformation.backend.documentproducer.db.manifest

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.embeddables.pojos.VariableManifestEntryId
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestEntriesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionDefaultValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionRecommendationsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionsRow
import com.terraformation.backend.documentproducer.db.VariableManifestStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.model.NewVariableManifestModel
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import org.jooq.DSLContext

data class ManifestImportResult(
    val newVersion: VariableManifestId?,
    val message: String,
    val results: List<String>,
    val errors: List<String>,
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
    lateinit var csvVariables: List<CsvSectionVariable>
    lateinit var csvVariableByStableId: Map<StableId, CsvSectionVariable>
    lateinit var variableManifestId: VariableManifestId

    /**
     * Map of full variable paths to CSV variables. This is the full "path" to the variable within
     * the hierarchy. For example "\tProject Details\tSummary\tProject Details", this allows us to
     * cache the values in memory without collisions due to non-uniqueness of the variable name.
     */
    lateinit var csvVariableByPath: Map<String, CsvSectionVariable>

    /**
     * Map of full variable paths to CSV variables that are children of the path. The children are
     * in the same order they appear in the manifest.
     */
    lateinit var csvVariablesByParentPath: Map<String, List<CsvSectionVariable>>

    val csvVariableNormalizer = CsvVariableNormalizer()
    val defaultTextVariableByStableId = mutableMapOf<String, Variable?>()

    var newVariablesImported = 0
    var variablesAttachedToNewManifest = 0

    val results = mutableListOf<String>()
    val errors = mutableListOf<String>()

    fun importCsv(
        documentTemplateId: DocumentTemplateId,
        inputBytes: ByteArray,
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

          // Import all sections
          importCsvVariables(csvVariables = csvVariables)

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
            listOf("Exception thrown while importing: $e") + errors,
        )
      }

      return ManifestImportResult(variableManifestId, "Success", results, errors)
    }

    private fun useExistingVariables(documentTemplateId: DocumentTemplateId) {
      val existingManifestId =
          variableManifestStore.fetchVariableManifestByDocumentTemplate(documentTemplateId)?.id
              ?: return
      val existingSectionVariables =
          variableStore
              .fetchManifestVariables(existingManifestId)
              .filterIsInstance<SectionVariable>()

      // Child variables such as subsections are not in the top-level list, so we need to
      // recursively process them.
      fun processVariables(variables: List<SectionVariable>) {
        variables.forEach { variable ->
          val csvVariable = csvVariableByStableId[variable.stableId]
          if (csvVariable != null) {
            if (canReuseExistingVariable(csvVariable, variable)) {
              csvVariable.variableId = variable.id
            } else {
              csvVariable.replacesVariableId = variable.id
            }
          }

          processVariables(variable.children)
        }
      }

      processVariables(existingSectionVariables)

      existingSectionVariables.forEach { markMovedSubsectionsNonReusable(it) }

      // At this point, tables and top-level sections are marked as non-reusable if any of their
      // children are non-reusable. But some of the children might still be marked as reusable, so
      // we need to propagate the non-reusability back down the hierarchy.
      existingSectionVariables
          .mapNotNull { csvVariableByStableId[it.stableId] }
          .filter { it.variableId == null }
          .forEach { markChildrenNonReusable(it) }
    }

    /**
     * If a section is new but its children already exist, mark the children as non-reusable. Apply
     * the same logic to each child section's children, walking the section tree to find
     * transplanted branches.
     */
    private fun markMovedSubsectionsNonReusable(variable: SectionVariable) {
      if (csvVariableByStableId[variable.stableId] != null) {
        variable.children.forEach { markMovedSubsectionsNonReusable(it) }
      } else {
        fun findExistingSubsectionsStillInCsv(variable: SectionVariable): List<CsvSectionVariable> {
          return listOfNotNull(csvVariableByStableId[variable.stableId]) +
              variable.children.flatMap { findExistingSubsectionsStillInCsv(it) }
        }

        findExistingSubsectionsStillInCsv(variable).forEach { markSectionNonReusable(it) }
      }
    }

    private fun importCsvVariables(
        csvVariables: List<CsvSectionVariable>,
    ) {
      csvVariables
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

      val notImported = csvVariables.filter { it.variableId == null }
      if (notImported.isNotEmpty()) {
        notImported.forEach { log.debug("Variable not imported: $it") }
        throw IllegalStateException("Some variables could not be imported")
      }
    }

    private fun importSectionVariable(csvVariable: CsvSectionVariable) {
      val parentVariable = csvVariable.parentPath?.let { csvVariableByPath[it] }

      variableStore.importSectionVariable(
          VariableSectionsRow(
              variableId = csvVariable.variableId,
              variableTypeId = VariableType.Section,
              parentVariableId = parentVariable?.variableId,
              parentVariableTypeId = if (parentVariable != null) VariableType.Section else null,
              renderHeading = !csvVariable.isNonNumberedSection,
          )
      )
    }

    private fun importDefaultSectionText(csvVariable: CsvSectionVariable) {
      if (csvVariable.defaultSectionText != null) {
        val regex = Regex("(.*?)(?:\\{\\{(?:[^}]*-\\s*)?([0-9]+)}}|$)", RegexOption.DOT_MATCHES_ALL)
        val textVariablePairs =
            regex.findAll(csvVariable.defaultSectionText).map { it.groupValues.drop(1) }

        // This used to be an Int var but it triggered https://youtrack.jetbrains.com/issue/KT-76632
        val listPosition = AtomicInteger(1)

        val defaultValuesRows =
            textVariablePairs
                .flatMap { (textValue, variableStableId) ->
                  val textRow =
                      if (textValue.isNotEmpty()) {
                        VariableSectionDefaultValuesRow(
                            variableId = csvVariable.variableId,
                            variableTypeId = VariableType.Section,
                            variableManifestId = variableManifestId,
                            listPosition = listPosition.getAndAdd(1),
                            textValue = textValue,
                        )
                      } else {
                        null
                      }

                  val variableRow =
                      if (variableStableId.isNotEmpty()) {
                        val referencedVariable =
                            defaultTextVariableByStableId.computeIfAbsent(variableStableId) {
                              variableStore.fetchByStableId(StableId(variableStableId))
                            }

                        if (referencedVariable != null) {
                          VariableSectionDefaultValuesRow(
                              variableId = csvVariable.variableId,
                              variableTypeId = VariableType.Section,
                              variableManifestId = variableManifestId,
                              listPosition = listPosition.getAndAdd(1),
                              usedVariableId = referencedVariable.id,
                              usedVariableTypeId = referencedVariable.type,
                              usageTypeId = VariableUsageType.Injection,
                              displayStyleId = VariableInjectionDisplayStyle.Inline,
                          )
                        } else {
                          errors.add(
                              "Variable in default section text does not exist - position: " +
                                  "${csvVariable.position}, referenced variable stable ID: $variableStableId"
                          )
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

    private fun importRecommendedVariables(csvVariable: CsvSectionVariable) {
      csvVariable.recommendedVariables.forEach { recommended ->
        val recommendedId = csvVariableByPath["\t$recommended"]?.variableId
        if (recommendedId != null) {
          variableStore.insertSectionRecommendation(
              VariableSectionRecommendationsRow(
                  recommendedVariableId = recommendedId,
                  sectionVariableId = csvVariable.variableId,
                  sectionVariableTypeId = VariableType.Section,
                  variableManifestId = variableManifestId,
              )
          )
        } else {
          errors.add(
              "Recommended variable does not exist - position: ${csvVariable.position}, " +
                  "recommended: $recommended"
          )
        }
      }
    }

    /**
     * Inserts a variable into the database. If the variable already exists and is being reused,
     * inserts a manifest entry pointing to the existing variable; otherwise inserts a new variable.
     *
     * Modifies [csvVariable] to set the variable ID and manifest entry key.
     */
    private fun importVariable(csvVariable: CsvSectionVariable) {
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
        importSectionVariable(csvVariable)
      }

      importDefaultSectionText(csvVariable)
      importRecommendedVariables(csvVariable)
    }

    private fun canImportVariable(csvVariable: CsvSectionVariable): Boolean {
      // If the variable has a variable manifest entry key, it has already been imported.
      return csvVariable.variableManifestEntryKey == null
    }

    /**
     * Returns true if the list of child sections in a CSV variable is the same as the children of
     * an existing section variable.
     */
    private fun hasSameSubsections(
        csvVariable: CsvSectionVariable,
        section: SectionVariable,
    ): Boolean {
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
    private fun canReuseExistingVariable(
        csvVariable: CsvSectionVariable,
        variable: Variable,
    ): Boolean {
      return csvVariable.description == variable.description &&
          csvVariable.name == variable.name &&
          csvVariable.stableId == variable.stableId &&
          variable is SectionVariable &&
          csvVariable.isNonNumberedSection == !variable.renderHeading &&
          hasSameSubsections(csvVariable, variable)
    }

    private fun markSectionNonReusable(csvVariable: CsvSectionVariable) {
      if (csvVariable.variableId != null) {
        csvVariable.replacesVariableId = csvVariable.variableId
        csvVariable.variableId = null
      }
    }

    private fun markChildrenNonReusable(csvVariable: CsvSectionVariable) {
      csvVariablesByParentPath[csvVariable.variablePath]?.forEach { child ->
        markSectionNonReusable(child)
        markChildrenNonReusable(child)
      }
    }
  }

  private class ImportAbortedDueToErrorsException : RuntimeException()
}
