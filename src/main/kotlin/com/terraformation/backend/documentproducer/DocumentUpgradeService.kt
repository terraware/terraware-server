package com.terraformation.pdd.document

import com.terraformation.pdd.document.db.DocumentStore
import com.terraformation.pdd.jooq.DocumentId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.tables.daos.VariableManifestsDao
import com.terraformation.pdd.variable.db.VariableStore
import com.terraformation.pdd.variable.db.VariableValueStore
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
                variableValueStore)
            .calculateOperations()

    dslContext.transaction { _ ->
      documentStore.updateDocument(documentId) { it.copy(variableManifestId = newManifestId) }
      variableValueStore.updateValues(operations)
    }
  }
}
