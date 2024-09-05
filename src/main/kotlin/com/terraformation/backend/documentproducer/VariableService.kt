package com.terraformation.backend.documentproducer

import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.variable.VariableImportResult
import com.terraformation.backend.documentproducer.db.variable.VariableImporter
import jakarta.inject.Named
import java.io.InputStream
import org.jooq.DSLContext

@Named
class VariableService(
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
    private val variableImporter: VariableImporter,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  fun importAllVariables(inputStream: InputStream): VariableImportResult {
    return dslContext.transactionResult { _ ->
      val result = variableImporter.import(inputStream)

      if (result.errors.isEmpty() && result.replacements.isNotEmpty()) {
        systemUser.run {
          val operationsByProject =
              VariableUpgradeCalculator(result.replacements, variableStore, variableValueStore)
                  .calculateOperations()
                  .groupBy { it.projectId }
          operationsByProject.values.forEach { variableValueStore.updateValues(it) }
        }
      }

      result
    }
  }
}
