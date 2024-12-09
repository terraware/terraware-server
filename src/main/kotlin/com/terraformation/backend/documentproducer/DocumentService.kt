package com.terraformation.backend.documentproducer

import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingDocumentModel
import com.terraformation.backend.documentproducer.model.NewDocumentModel
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class DocumentService(
    val dslContext: DSLContext,
    val documentStore: DocumentStore,
    val variableValueStore: VariableValueStore,
) {
  fun create(newDocumentModel: NewDocumentModel): ExistingDocumentModel {
    return dslContext.transactionResult { _ ->
      val model = documentStore.create(newDocumentModel)

      val operations =
          variableValueStore.calculateDefaultValues(model.projectId, model.variableManifestId)
      variableValueStore.updateValues(operations)

      model
    }
  }
}
