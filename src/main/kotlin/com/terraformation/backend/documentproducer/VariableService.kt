package com.terraformation.backend.documentproducer

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.Variable
import jakarta.inject.Named

@Named
class VariableService(
    private val documentStore: DocumentStore,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  fun fetchDocumentVariables(documentId: DocumentId): List<Variable> {
    requirePermissions { readDocument(documentId) }

    val document = documentStore.fetchDocumentById(documentId)
    val manifestVariables = variableStore.fetchManifestVariables(document.variableManifestId!!)

    val sectionVariableIds =
        manifestVariables.flatMap { parent ->
          when (parent) {
            is SectionVariable -> parent.children.map { child -> child.id }
            else -> emptyList()
          }
        }

    val injectedSectionValues =
        variableValueStore
            .listValues(projectId = document.projectId!!, variableIds = sectionVariableIds)
            .map { it.value }
            .filterIsInstance<SectionValueVariable>()

    val injectedSectionValueVariables =
        injectedSectionValues.map { variableStore.fetchVariable(it.usedVariableId) }

    return manifestVariables + injectedSectionValueVariables
  }
}
