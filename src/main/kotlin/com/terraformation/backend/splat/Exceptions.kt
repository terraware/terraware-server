package com.terraformation.backend.splat

import com.terraformation.backend.db.default_schema.FileId

data class SplatAdditionalFilesExistException(val fileId: FileId) :
    RuntimeException("Splat for file $fileId already has additional files")

data class SplatGenerationFailedException(val fileId: FileId) :
    Exception("Failed to generate splat for file $fileId")

data class SplatNotReadyException(val fileId: FileId) :
    Exception("Splat not ready for file $fileId")
