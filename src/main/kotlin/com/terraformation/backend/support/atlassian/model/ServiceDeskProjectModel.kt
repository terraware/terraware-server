package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A Jira service desk project.
 *
 * @param id the id Jira software uses to represent the service desk project
 * @param projectKey the project key that comes before the ticket number in a Jira project.
 *
 * For example: `ServiceDeskProjectModel(1, "PROJ")` represents a service desk in this url:
 * https://${your-domain}.atlassian.net/jira/servicedesk/projects/PROJ/
 *
 * Each issue in this service desk project will have an issue ID in this format: `PROJ-${number}`
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceDeskProjectModel(
    val id: Int,
    val projectKey: String,
)
