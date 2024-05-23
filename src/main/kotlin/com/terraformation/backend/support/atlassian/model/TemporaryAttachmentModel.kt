package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A temporary attachment uploaded to Jira cloud. It is the result from the first step of a two
 * stage process to attach a file to an Atlassian issue.
 *
 * @param temporaryAttachmentId the id Jira software uses to represent the uploaded attachment
 * @param fileName the file name of the uploaded attachment
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TemporaryAttachmentModel(
    val temporaryAttachmentId: String,
    val fileName: String,
)
