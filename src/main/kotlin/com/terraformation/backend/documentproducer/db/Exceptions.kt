package com.terraformation.pdd.document.db

import com.terraformation.pdd.db.EntityNotFoundException
import com.terraformation.pdd.db.MismatchedStateException
import com.terraformation.pdd.jooq.DocumentId
import com.terraformation.pdd.jooq.DocumentSavedVersionId
import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.VariableManifestId

class CannotSaveEmptyDocumentException(val documentId: DocumentId) :
    MismatchedStateException(
        "Cannot save version of document $documentId before any values are present")

class DocumentNotFoundException(documentId: DocumentId) :
    EntityNotFoundException("Document $documentId not found")

class MissingVariableManifestException(val methodologyId: MethodologyId) :
    MismatchedStateException("No variable manifest defined for methodology $methodologyId")

class SavedVersionNotFoundException(documentId: DocumentId, versionId: DocumentSavedVersionId) :
    EntityNotFoundException("Document $documentId has no saved version $versionId")

class UpgradeCannotChangeMethodologyException(
    val oldManifestId: VariableManifestId,
    val oldMethodologyId: MethodologyId,
    val newManifestId: VariableManifestId,
    val newMethodologyId: MethodologyId
) :
    MismatchedStateException(
        "Cannot upgrade from manifest $oldManifestId (methodology $oldMethodologyId) to manifest " +
            "$newManifestId which is for a different methodology $newMethodologyId")

package com.terraformation.pdd.variable.db

import com.terraformation.pdd.db.EntityNotFoundException
import com.terraformation.pdd.db.MismatchedStateException
import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.VariableId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.VariableType
import com.terraformation.pdd.jooq.VariableValueId

class CircularReferenceException(val variableIds: Collection<VariableId>) :
  IllegalStateException("Circular reference detected in variables: $variableIds")

class NoManifestForMethodologyException(val methodologyId: MethodologyId) :
  EntityNotFoundException("Methodology $methodologyId has no variable manifest")

class RowInWrongTableException(val columnVariableId: VariableId, val rowValueId: VariableValueId) :
  MismatchedStateException(
      "Row $rowValueId is in a different table than column $columnVariableId")

class VariableIncompleteException(val variableId: VariableId) :
  IllegalStateException("Variable $variableId missing required configuration data")

class VariableInTableException(val variableId: VariableId) :
  MismatchedStateException(
      "Variable $variableId is a table column so must be used in a table row")

class VariableManifestNotFoundException(val manifestId: VariableManifestId) :
  EntityNotFoundException("Variable manifest $manifestId not found")

class VariableNotFoundException(val variableId: VariableId) :
  EntityNotFoundException("Variable $variableId not found")

class VariableNotInManifestException(
  val variableId: VariableId,
  val manifestId: VariableManifestId
) : EntityNotFoundException("Variable $variableId is not in manifest $manifestId")

class VariableNotInTableException(val variableId: VariableId) :
  MismatchedStateException(
      "Variable $variableId is not a table column so cannot be used in a table row")

class VariableNotListException(val variableId: VariableId) :
  MismatchedStateException("Variable $variableId is not a list")

class VariableTypeMismatchException(val variableId: VariableId, val expectedType: VariableType) :
  MismatchedStateException("Variable $variableId is not of type $expectedType")

class VariableValueIncompleteException(val valueId: VariableValueId) :
  IllegalStateException("Variable value $valueId missing required data")

class VariableValueInvalidException(val variableId: VariableId, val reason: String? = null) :
  IllegalArgumentException(
      listOfNotNull("Invalid value for $variableId", reason).joinToString(": "))

class VariableValueNotFoundException(val valueId: VariableValueId) :
  EntityNotFoundException("Value $valueId not found")

class VariableValueTypeMismatchException(
  val valueId: VariableValueId,
  val expectedType: VariableType
) : MismatchedStateException("Value $valueId is not of type $expectedType")
