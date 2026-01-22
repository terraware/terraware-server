package com.terraformation.backend.splat

import com.terraformation.backend.db.default_schema.FileId

data class SplatGenerationFailedException(val fileId: FileId) :
    Exception("Failed to generate splat for file $fileId")

data class SplatNotReadyException(val fileId: FileId) :
    Exception("Splat not ready for file $fileId")
