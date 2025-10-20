package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.PersistentEvent

data class OrganizationDeletedEventV1(val organizationId: OrganizationId) : PersistentEvent

typealias OrganizationDeletedEvent = OrganizationDeletedEventV1
