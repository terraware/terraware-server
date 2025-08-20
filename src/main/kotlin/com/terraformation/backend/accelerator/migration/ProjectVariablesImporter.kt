package com.terraformation.backend.accelerator.migration

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DateValue
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.EmailValue
import com.terraformation.backend.documentproducer.model.LinkValue
import com.terraformation.backend.documentproducer.model.LinkValueDetails
import com.terraformation.backend.documentproducer.model.NumberValue
import com.terraformation.backend.documentproducer.model.ReplaceValuesOperation
import com.terraformation.backend.documentproducer.model.SelectOption
import com.terraformation.backend.documentproducer.model.SelectValue
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TextValue
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.importer.processCsvFile
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.time.LocalDate
import org.jooq.impl.DefaultDSLContext

@Named
class ProjectVariablesImporter(
    private val applicationStore: ApplicationStore,
    private val dslContext: DefaultDSLContext,
    private val systemUser: SystemUser,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  fun importCsv(inputStream: InputStream, ignoreUnknownVariables: Boolean) {
    requirePermissions { createEntityWithOwner(systemUser.userId) }

    systemUser.run {
      val variablesByName = variableStore.fetchAllNonSectionVariables().associateBy { it.name }

      dslContext.transaction { _ ->
        // Variable for each column except the first two.
        lateinit var variables: List<Variable?>

        processCsvFile(inputStream, skipHeaderRow = false) { values, rowNumber, addError ->
          try {
            if (rowNumber == 1) {
              variables =
                  values.drop(2).map { name ->
                    if (name == null) {
                      throw ImportError(
                          "All header columns except the first two must have variable names"
                      )
                    }

                    if (!ignoreUnknownVariables && name !in variablesByName) {
                      addError("Variable not found: $name")
                    }

                    variablesByName[name]
                  }
            }

            // Rows 2-4 are additional headers.
            if (rowNumber < 5) {
              return@processCsvFile
            }

            val dealName = values[1] ?: throw ImportError("Missing deal name")
            val application =
                applicationStore.fetchOneByInternalName(dealName)
                    ?: throw ImportError(
                        "Deal $dealName has not been imported yet; import the project setup sheet first."
                    )
            val currentValues =
                variableValueStore
                    .listValues(
                        projectId = application.projectId,
                        variableIds = variables.mapNotNull { it?.id },
                    )
                    .associateBy { it.variableId }

            val operations =
                values.drop(2).zip(variables).mapNotNull { (value, variable) ->
                  if (value != null && variable != null) {
                    val baseProperties =
                        BaseVariableValueProperties(
                            null,
                            application.projectId,
                            0,
                            variable.id,
                            null,
                        )

                    val typedValue =
                        when (variable.type) {
                          VariableType.Number -> {
                            val bigDecimalValue =
                                value.toBigDecimalOrNull()
                                    ?: throw ImportError(
                                        "${variable.name} value $value is not a number"
                                    )
                            if (
                                (currentValues[variable.id]?.value as? NumberValue<*>)?.value !=
                                    bigDecimalValue
                            ) {
                              NumberValue(baseProperties, bigDecimalValue)
                            } else {
                              null
                            }
                          }
                          VariableType.Text -> {
                            if (
                                (currentValues[variable.id]?.value as? TextValue<*>)?.value != value
                            ) {
                              TextValue(baseProperties, value)
                            } else {
                              null
                            }
                          }
                          VariableType.Date -> {
                            val localDate =
                                try {
                                  // Some of the "date" values in the sheet are timestamps
                                  val datePart = value.substringBefore('T')
                                  LocalDate.parse(datePart)
                                } catch (_: Exception) {
                                  throw ImportError(
                                      "${variable.name} value $value is not a date in YYYY-MM-DD format"
                                  )
                                }
                            if (
                                (currentValues[variable.id]?.value as? DateValue<*>)?.value !=
                                    localDate
                            ) {
                              DateValue(baseProperties, localDate)
                            } else {
                              null
                            }
                          }
                          VariableType.Email -> {
                            if (
                                (currentValues[variable.id]?.value as? EmailValue<*>)?.value !=
                                    value
                            ) {
                              EmailValue(baseProperties, value)
                            } else {
                              null
                            }
                          }
                          VariableType.Select -> {
                            val optionIds = parseOptions(value, variable as SelectVariable)
                            if (
                                (currentValues[variable.id]?.value as? SelectValue<*>)?.value !=
                                    optionIds
                            ) {
                              SelectValue(baseProperties, optionIds)
                            } else {
                              null
                            }
                          }
                          VariableType.Link -> {
                            val url =
                                try {
                                  URI.create(value)
                                } catch (e: Exception) {
                                  throw ImportError(
                                      "${variable.name} value $value is not a valid URL"
                                  )
                                }
                            if (
                                (currentValues[variable.id]?.value as? LinkValue<*>)?.value?.url !=
                                    url
                            ) {
                              LinkValue(baseProperties, LinkValueDetails(url, null))
                            } else {
                              null
                            }
                          }
                          VariableType.Image,
                          VariableType.Table,
                          VariableType.Section ->
                              throw ImportError(
                                  "Import of ${variable.type} variables is not supported"
                              )
                        }

                    typedValue?.let {
                      ReplaceValuesOperation(application.projectId, variable.id, null, listOf(it))
                    }
                  } else if (variable != null && variable.id in currentValues) {
                    DeleteValueOperation(application.projectId, currentValues[variable.id]!!.id)
                  } else {
                    null
                  }
                }

            variableValueStore.updateValues(operations)
          } catch (e: Exception) {
            addError(e.message ?: e.toString())
          }
        }
      }
    }
  }

  /**
   * Parses a string with a list of options into a list of option IDs for a variable.
   *
   * The spreadsheet is inconsistent about how option values are delimited, and in some cases, it
   * uses delimiters such as commas that also appear in the option values. So a simple "split on
   * delimiters" approach won't work. Instead, we walk through the possible options to see if there
   * is any combination of them that matches the entire value. If there's more than one matching
   * combination, which could happen if some option names are substrings of other options, it's
   * flagged as an error.
   */
  private fun parseOptions(value: String, variable: SelectVariable): Set<VariableSelectOptionId> {
    val valueWithoutLeadingDelimiters = value.trimStart(',', ';', ' ', '\n', '\r')
    var matchingOption: SelectOption? = null
    var laterOptionIds: Set<VariableSelectOptionId>? = null
    var laterOptionsError: ImportError? = null

    if (valueWithoutLeadingDelimiters.isEmpty()) {
      return emptySet()
    }

    for (option in variable.options) {
      if (valueWithoutLeadingDelimiters.startsWith(option.name, ignoreCase = true)) {
        try {
          laterOptionIds =
              parseOptions(valueWithoutLeadingDelimiters.substring(option.name.length), variable)

          if (matchingOption != null) {
            throw ImportError(
                "Ambiguous value: could be either \"${matchingOption.name}\" or \"${option.name}\""
            )
          } else {
            matchingOption = option
          }
        } catch (e: ImportError) {
          // Some part of the value after this couldn't be parsed. But there might be more than one
          // option that matches valueWithoutLeadingDelimiters, so stash the error until we've tried
          // all the other options to see if one of them lets us parse the whole value.
          if (laterOptionsError == null) {
            laterOptionsError = e
          }
        }
      }
    }

    if (matchingOption != null && laterOptionIds != null) {
      return laterOptionIds + matchingOption.id
    } else if (laterOptionsError != null) {
      throw laterOptionsError
    } else {
      throw ImportError("None of the variable's options matches $valueWithoutLeadingDelimiters")
    }
  }

  private class ImportError(message: String) : Exception(message)
}
