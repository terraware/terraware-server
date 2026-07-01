package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.i18n.Messages

/**
 * Published when a project's details other than its name are changed. Name changes are covered by
 * [ProjectRenamedEvent].
 */
data class ProjectUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val projectId: ProjectId,
) : FieldsUpdatedPersistentEvent, ProjectPersistentEvent {
  data class Values(
      val botanicalCountryCode: String? = null,
      val countryCode: String? = null,
      val description: String? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "botanicalCountryCode",
              messages.botanicalCountryName(changedFrom.botanicalCountryCode),
              messages.botanicalCountryName(changedTo.botanicalCountryCode),
          ),
          createUpdatedField("countryCode", changedFrom.countryCode, changedTo.countryCode),
          createUpdatedField("description", changedFrom.description, changedTo.description),
      )
}

typealias ProjectUpdatedEvent = ProjectUpdatedEventV1

typealias ProjectUpdatedEventValues = ProjectUpdatedEventV1.Values
