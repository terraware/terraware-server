package com.terraformation.backend.documentproducer

import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.daos.VariableManifestsDao
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class DocumentUpgradeService(
    private val documentStore: DocumentStore,
    private val dslContext: DSLContext,
    private val variableManifestsDao: VariableManifestsDao,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  fun upgradeManifest(documentId: DocumentId, newManifestId: VariableManifestId) {
    val operations =
        DocumentUpgradeCalculator(
                documentId,
                newManifestId,
                documentStore,
                variableManifestsDao,
                variableStore,
                variableValueStore,
            )
            .calculateOperations()

    dslContext.transaction { _ ->
      documentStore.updateDocument(documentId) { it.copy(variableManifestId = newManifestId) }
      variableValueStore.updateValues(operations)
    }
  }
}
