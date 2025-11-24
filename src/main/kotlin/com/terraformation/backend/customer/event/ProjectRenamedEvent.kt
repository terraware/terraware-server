package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.db.EventUpgradeUtils
import com.terraformation.backend.i18n.Messages

data class ProjectRenamedEventV1(
    val name: String,
    val organizationId: OrganizationId,
    val projectId: ProjectId,
) : UpgradableEvent {
  override fun toNextVersion(
      eventLogId: EventLogId,
      eventUpgradeUtils: EventUpgradeUtils,
  ): ProjectRenamedEventV2 {
    val previousName = eventUpgradeUtils.getPreviousProjectNameFromV1Events(projectId, eventLogId)

    return ProjectRenamedEventV2(
        changedFrom = ProjectRenamedEventV2.Values(previousName),
        changedTo = ProjectRenamedEventV2.Values(name),
        organizationId = organizationId,
        projectId = projectId,
    )
  }
}

/** Published when a project's name is changed. */
data class ProjectRenamedEventV2(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val projectId: ProjectId,
) : FieldsUpdatedPersistentEvent, ProjectPersistentEvent {
  data class Values(val name: String?)

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(createUpdatedField("name", changedFrom.name, changedTo.name))
}

typealias ProjectRenamedEvent = ProjectRenamedEventV2

typealias ProjectRenamedEventValues = ProjectRenamedEventV2.Values
