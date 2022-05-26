package com.terraformation.backend.search

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId

/**
 * A scoping model to use with [SearchTable.conditionForScope] that generates a jOOQ condition to
 * scope queries.
 */
sealed interface SearchScope

data class OrganizationIdScope(val organizationId: OrganizationId) : SearchScope

data class FacilityIdScope(val facilityId: FacilityId) : SearchScope
