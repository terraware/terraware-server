package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.EntityUpdatedPersistentEvent

data class OrganizationRenamedEventV1(
    override val organizationId: OrganizationId,
    val name: String,
) : EntityUpdatedPersistentEvent, OrganizationPersistentEvent

typealias OrganizationRenamedEvent = OrganizationRenamedEventV1
