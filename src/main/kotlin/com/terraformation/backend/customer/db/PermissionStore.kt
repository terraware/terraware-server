package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.USERS
import javax.annotation.ManagedBean
import org.jooq.DSLContext

/**
 * Stores and retrieves user permission information.
 *
 * If you want to read the current user's roles, you will usually want to use the properties on
 * [IndividualUser] instead of the `fetch` methods on this class.
 */
@ManagedBean
class PermissionStore(private val dslContext: DSLContext) {
  /**
   * Returns a user's role in each facility under the organizations they're in. The roles are
   * organization-level, so will be the same across all facilities of a given organization.
   */
  fun fetchFacilityRoles(userId: UserId): Map<FacilityId, Role> {
    // Users have read access to all facilities under projects they're a member of, regardless
    // of role. Admins have access to all facilities
    return dslContext
        .select(FACILITIES.ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .join(USERS)
        .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
        .join(FACILITIES)
        .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(FACILITIES.ORGANIZATION_ID))
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .fetchMap({ row -> row.value1() }, { row -> row.value2()?.let { Role.of(it) } })
  }

  /** Returns a user's role in each of their organizations. */
  fun fetchOrganizationRoles(userId: UserId): Map<OrganizationId, Role> {
    return dslContext
        .select(ORGANIZATION_USERS.ORGANIZATION_ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .fetchMap({ row -> row.value1() }, { row -> row.value2()?.let { Role.of(it) } })
  }
}
