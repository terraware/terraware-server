package com.terraformation.backend.customer.event

import com.terraformation.backend.db.OrganizationId

/**
 * Published when we start deleting all the data related to an organization, but before the
 * organization has actually been deleted from the database.
 */
data class OrganizationDeletionStartedEvent(val organizationId: OrganizationId)
