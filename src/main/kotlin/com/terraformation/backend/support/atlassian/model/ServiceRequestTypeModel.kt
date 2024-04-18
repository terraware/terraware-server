package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A Jira service request type.
 *
 * @param id the id Jira software uses to represent the service request type
 * @param name the name of the service request type
 * @param description the description of the service request type
 *
 * For example:
 * ```
 * ServiceRequestTypeModel(1, "Report a Bug", "Tell us the problems you're experiencing.")
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceRequestTypeModel(
    val id: Int,
    val name: String,
    val description: String,
)
