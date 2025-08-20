package com.terraformation.backend.documentproducer

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.db.VariableValueNotFoundException
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.VariableValueTypeMismatchException
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.ExistingImageValue
import com.terraformation.backend.documentproducer.model.ImageValueDetails
import com.terraformation.backend.documentproducer.model.NewImageValue
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.NewFileMetadata
import jakarta.inject.Named
import java.io.InputStream

/** Manages storage of files that are values of image variables. */
@Named
class VariableFileService(
    private val fileService: FileService,
    private val variableValueStore: VariableValueStore,
) {
  fun readImageValue(
      projectId: ProjectId,
      valueId: VariableValueId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    val existingValue = variableValueStore.fetchOneById(valueId)
    if (existingValue.projectId != projectId) {
      throw VariableValueNotFoundException(valueId)
    }

    if (existingValue is ExistingImageValue) {
      return fileService.readFile(existingValue.value.fileId, maxWidth, maxHeight)
    } else {
      throw VariableValueTypeMismatchException(valueId, VariableType.Image)
    }
  }

  fun storeImageValue(
      data: InputStream,
      metadata: NewFileMetadata,
      base: BaseVariableValueProperties<Nothing?>,
      caption: String?,
      isAppend: Boolean,
  ): VariableValueId {
    lateinit var valueId: VariableValueId

    fileService.storeFile("imageValue", data, metadata) { fileId ->
      val effectiveBase =
          if (isAppend) {
            base.copy(
                listPosition =
                    variableValueStore.fetchNextListPosition(base.projectId, base.variableId)
            )
          } else {
            base
          }

      val newImageValue = NewImageValue(effectiveBase, ImageValueDetails(caption, fileId))

      valueId = variableValueStore.writeValue(newImageValue)
    }

    return valueId
  }
}
