package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent

data class OrganizationCreatedEventV1(
    override val organizationId: OrganizationId,
    val name: String,
) : EntityCreatedPersistentEvent, OrganizationPersistentEvent

typealias OrganizationCreatedEvent = OrganizationCreatedEventV1
