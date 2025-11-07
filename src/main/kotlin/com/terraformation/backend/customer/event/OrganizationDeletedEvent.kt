package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent

data class OrganizationDeletedEventV1(override val organizationId: OrganizationId) :
    EntityDeletedPersistentEvent, OrganizationPersistentEvent

typealias OrganizationDeletedEvent = OrganizationDeletedEventV1
