package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.db.EventUpgradeUtils

data class OrganizationRenamedEventV1(
    val organizationId: OrganizationId,
    val name: String,
) : UpgradableEvent {
  override fun toNextVersion(
      eventLogId: EventLogId,
      eventUpgradeUtils: EventUpgradeUtils,
  ): OrganizationRenamedEventV2 {
    val previousName = eventUpgradeUtils.getPreviousOrganizationName(organizationId, eventLogId)
    return OrganizationRenamedEventV2(
        changedFrom = OrganizationRenamedEventV2.Values(previousName),
        changedTo = OrganizationRenamedEventV2.Values(name),
        organizationId = organizationId,
    )
  }
}

data class OrganizationRenamedEventV2(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
) : FieldsUpdatedPersistentEvent, OrganizationPersistentEvent {
  data class Values(val name: String?)

  override fun listUpdatedFields() =
      listOfNotNull(createUpdatedField("name", changedFrom.name, changedTo.name))
}

typealias OrganizationRenamedEvent = OrganizationRenamedEventV2

typealias OrganizationRenamedEventValues = OrganizationRenamedEventV2.Values
