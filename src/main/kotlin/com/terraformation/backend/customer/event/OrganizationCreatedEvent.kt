package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.db.EventUpgradeUtils

data class OrganizationCreatedEventV1(val organizationId: OrganizationId) : UpgradableEvent {
  override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils): OrganizationCreatedEventV2 {
    val name = eventUpgradeUtils.getOrganizationName(organizationId) ?: "[deleted]"
    return OrganizationCreatedEventV2(organizationId, name)
  }
}

data class OrganizationCreatedEventV2(val organizationId: OrganizationId, val name: String) :
    PersistentEvent

typealias OrganizationCreatedEvent = OrganizationCreatedEventV2
