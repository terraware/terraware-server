package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A Jira service request type.
 *
 * @param id the id Jira software uses to represent the service request type
 * @param name the name of the service request type. Must match [SupportRequestType] json value
 *
 * For example:
 * ```
 * ServiceRequestTypeModel(1, "Bug Report")
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraServiceRequestTypeModel(
    val id: Int,
    val name: String,
)
