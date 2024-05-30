package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * The body of a Jira service request ticket. Contains user-input title and description of the
 * ticket.
 *
 * @param summary the title of a service request ticket
 * @param description the description of a service request ticket
 *
 * For example:
 * ```
 * ServiceRequestFieldsModel(
 *   "New Feature: Ability to submit support request ticket via API",
 *   "As a user, I would like to be able to submit a support request via REST APIs.")
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraServiceRequestFieldsModel(
    val summary: String,
    val description: String,
)
