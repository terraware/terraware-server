package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.docprod.DocumentId

data class NewSavedVersionModel(
    val documentId: DocumentId,
    val isSubmitted: Boolean,
    val name: String,
)
