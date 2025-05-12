package com.terraformation.backend.documentproducer

import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.variable.VariableImportResult
import com.terraformation.backend.documentproducer.db.variable.VariableImporter
import com.terraformation.backend.documentproducer.event.VariablesUpdatedEvent
import com.terraformation.backend.documentproducer.event.VariablesUploadedEvent
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class VariableService(
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
    private val variableImporter: VariableImporter,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val eventPublisher: ApplicationEventPublisher,
) {
  private val log = perClassLogger()

  fun importAllVariables(inputStream: InputStream): VariableImportResult {
    return dslContext.transactionResult { _ ->
      val result = variableImporter.import(inputStream)

      if (result.errors.isEmpty() && result.replacements.isNotEmpty()) {
        systemUser.run {
          val operationsByProject =
              VariableUpgradeCalculator(result.replacements, variableStore, variableValueStore)
                  .calculateOperations()
                  .groupBy { it.projectId }
          operationsByProject.values.forEach { operations ->
            variableValueStore.updateValues(operations, triggerWorkflows = false)
          }

          variableValueStore.upgradeSectionDefaultValues(result.replacements)
          variableValueStore.upgradeSectionValueVariables(result.replacements)
        }
      }

      eventPublisher.publishEvent(VariablesUploadedEvent())
      result
    }
  }

  fun upgradeAllVariables() {
    dslContext.transaction { _ ->
      variableStore.fetchAllNonSectionVariables().forEach { newVariable ->
        variableStore
            .fetchReplacedVariables(newVariable.id)
            .reversed() // Walk backward through the replacement chain
            .map { variableStore.fetchOneVariable(it) }
            .forEach { oldVariable ->
              val columnReplacements =
                  if (newVariable is TableVariable && oldVariable is TableVariable) {
                    newVariable.columns
                        .mapNotNull { newColumn ->
                          oldVariable.columns
                              .firstOrNull { oldColumn ->
                                oldColumn.variable.stableId == newColumn.variable.stableId
                              }
                              ?.let { oldColumn -> oldColumn.variable.id to newColumn.variable.id }
                        }
                        .toMap()
                  } else {
                    emptyMap()
                  }

              val replacements = mapOf(oldVariable.id to newVariable.id) + columnReplacements

              val operationsByProject =
                  VariableUpgradeCalculator(replacements, variableStore, variableValueStore)
                      .calculateOperations()
                      .groupBy { it.projectId }

              operationsByProject.values.forEach { operations ->
                variableValueStore.updateValues(operations, triggerWorkflows = false)
              }

              variableValueStore.upgradeSectionDefaultValues(replacements)
              variableValueStore.upgradeSectionValueVariables(replacements)
            }
      }
    }
  }

  @EventListener
  fun on(event: VariablesUpdatedEvent) {
    log.info(event.message)
    variableStore.clearCache()
  }
}
