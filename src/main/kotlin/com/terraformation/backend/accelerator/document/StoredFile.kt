package com.terraformation.backend.accelerator.document

/** Information about a file that was saved in a document store. */
data class StoredFile(
    /**
     * The name of the file that was actually stored. This may differ from the requested filename if
     * the document store supports automatically renaming to avoid name collisions.
     */
    val storedName: String,
    /** The file's location in the document store. The meaning of this is store-specific. */
    val location: String,
)
