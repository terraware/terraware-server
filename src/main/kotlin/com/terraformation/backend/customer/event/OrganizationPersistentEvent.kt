package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.PersistentEvent

sealed interface OrganizationPersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
}
