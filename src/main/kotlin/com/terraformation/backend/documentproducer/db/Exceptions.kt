package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableValueId

class CannotSaveEmptyDocumentException(val documentId: DocumentId) :
    MismatchedStateException(
        "Cannot save version of document $documentId before any values are present"
    )

class DocumentNotFoundException(documentId: DocumentId) :
    EntityNotFoundException("Document $documentId not found")

class MissingVariableManifestException(val documentTemplateId: DocumentTemplateId) :
    MismatchedStateException(
        "No variable manifest defined for document template $documentTemplateId"
    )

class SavedVersionNotFoundException(documentId: DocumentId, versionId: DocumentSavedVersionId) :
    EntityNotFoundException("Document $documentId has no saved version $versionId")

class UpgradeCannotChangeDocumentTemplateException(
    val oldDocumentTemplateId: DocumentTemplateId,
    val oldManifestId: VariableManifestId,
    val newDocumentTemplateId: DocumentTemplateId,
    val newManifestId: VariableManifestId,
) :
    MismatchedStateException(
        "Cannot upgrade from manifest $oldManifestId (document template $oldDocumentTemplateId) to manifest " +
            "$newManifestId which is for a different document template $newDocumentTemplateId"
    )

class CircularReferenceException(val variableIds: Collection<VariableId>) :
    IllegalStateException("Circular reference detected in variables: $variableIds")

class NoManifestForDocumentTemplateException(val documentTemplateId: DocumentTemplateId) :
    EntityNotFoundException("Document Template $documentTemplateId has no variable manifest")

class RowInWrongTableException(val columnVariableId: VariableId, val rowValueId: VariableValueId) :
    MismatchedStateException(
        "Row $rowValueId is in a different table than column $columnVariableId"
    )

class VariableIncompleteException(val variableId: VariableId) :
    IllegalStateException("Variable $variableId missing required configuration data")

class VariableInTableException(val variableId: VariableId) :
    MismatchedStateException(
        "Variable $variableId is a table column so must be used in a table row"
    )

class VariableManifestNotFoundException(val manifestId: VariableManifestId) :
    EntityNotFoundException("Variable manifest $manifestId not found")

class VariableNotFoundException(val variableId: VariableId) :
    EntityNotFoundException("Variable $variableId not found")

class VariableNotInManifestException(
    val variableId: VariableId,
    val manifestId: VariableManifestId,
) : EntityNotFoundException("Variable $variableId is not in manifest $manifestId")

class VariableNotInTableException(val variableId: VariableId) :
    MismatchedStateException(
        "Variable $variableId is not a table column so cannot be used in a table row"
    )

class VariableNotListException(val variableId: VariableId) :
    MismatchedStateException("Variable $variableId is not a list")

class VariableTypeMismatchException(val variableId: VariableId, val expectedType: VariableType) :
    MismatchedStateException("Variable $variableId is not of type $expectedType")

class VariableValueIncompleteException(val valueId: VariableValueId) :
    IllegalStateException("Variable value $valueId missing required data")

class VariableValueInvalidException(val variableId: VariableId, val reason: String? = null) :
    IllegalArgumentException(
        listOfNotNull("Invalid value for $variableId", reason).joinToString(": ")
    )

class VariableValueNotFoundException(val valueId: VariableValueId) :
    EntityNotFoundException("Value $valueId not found")

class VariableValueTypeMismatchException(
    val valueId: VariableValueId,
    val expectedType: VariableType,
) : MismatchedStateException("Value $valueId is not of type $expectedType")
