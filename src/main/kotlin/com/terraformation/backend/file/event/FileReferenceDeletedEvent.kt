package com.terraformation.backend.file.event

import com.terraformation.backend.db.default_schema.FileId

/**
 * Published after a reference to a file has been deleted. This will cause the file to be deleted
 * from the file store if there are no other references.
 */
data class FileReferenceDeletedEvent(val fileId: FileId)
