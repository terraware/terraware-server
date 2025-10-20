package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.PersistentEvent

data class OrganizationRenamedEventV1(val organizationId: OrganizationId, val name: String) :
    PersistentEvent

typealias OrganizationRenamedEvent = OrganizationRenamedEventV1
