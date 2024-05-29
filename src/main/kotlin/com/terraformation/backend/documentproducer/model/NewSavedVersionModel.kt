package com.terraformation.pdd.document.model

import com.terraformation.pdd.jooq.DocumentId

data class NewSavedVersionModel(
    val documentId: DocumentId,
    val isSubmitted: Boolean,
    val name: String,
)
